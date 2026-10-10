package com.example.githubdesktop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.example.githubdesktop.auth.OAuthManager
import com.example.githubdesktop.auth.TokenStore
import com.example.githubdesktop.data.api.FailureKind
import com.example.githubdesktop.data.api.GitHubClient
import com.example.githubdesktop.data.api.toGitHubFailure
import com.example.githubdesktop.ui.AppViewModel
import com.example.githubdesktop.ui.screens.LoginScreen
import com.example.githubdesktop.ui.screens.RootNav
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private lateinit var tokenStore: TokenStore

    private val loggedIn = mutableStateOf(false)
    private val loginError = mutableStateOf<String?>(null)
    private val exchangingCode = mutableStateOf(false)
    private val patBusy = mutableStateOf(false)
    private val oauthConfigured = mutableStateOf(false)
    private val savedClientId = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = (application as GitHubApp).tokenStore

        // Restore user-supplied OAuth credentials (if any) so browser sign-in
        // stays enabled across restarts without rebuilding the app.
        OAuthManager.setRuntimeCredentials(
            tokenStore.getOAuthClientId().orEmpty(),
            tokenStore.getOAuthClientSecret().orEmpty()
        )
        oauthConfigured.value = OAuthManager.isConfigured
        savedClientId.value = tokenStore.getOAuthClientId().orEmpty()

        tokenStore.getToken()?.let {
            GitHubClient.setToken(it)
            loggedIn.value = true
        }
        handleRedirect(intent)

        setContent {
            if (loggedIn.value) {
                LaunchedEffect(Unit) { vm.loadUserAndRepos() }
                RootNav(
                    vm = vm,
                    login = tokenStore.getLogin() ?: "user",
                    onLogout = {
                        tokenStore.clear()
                        GitHubClient.setToken(null)
                        loggedIn.value = false
                    }
                )
            } else {
                LoginScreen(
                    configured = oauthConfigured.value,
                    oauthBusy = exchangingCode.value,
                    patBusy = patBusy.value,
                    errorMessage = loginError.value,
                    savedClientId = savedClientId.value,
                    onLogin = ::startLogin,
                    onPatLogin = ::startPatLogin,
                    onSaveOAuth = ::saveOAuthCredentials
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun startLogin() {
        loginError.value = null
        try {
            OAuthManager.launchLogin(this)
        } catch (e: OAuthManager.LoginException) {
            loginError.value = e.message
        }
    }

    private fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            loginError.value = "Enter both the Client ID and the Client Secret."
            return
        }
        tokenStore.saveOAuthCredentials(clientId, clientSecret)
        OAuthManager.setRuntimeCredentials(clientId, clientSecret)
        oauthConfigured.value = OAuthManager.isConfigured
        savedClientId.value = clientId
        loginError.value = null
    }

    /**
     * Token sign-in: works with zero setup. The token is verified with a
     * GET /user call before it is stored; failures never leave a bad token
     * behind.
     */
    private fun startPatLogin(rawToken: String) {
        val token = rawToken.trim()
        if (token.isBlank()) {
            loginError.value = "Paste your personal access token first."
            return
        }
        loginError.value = null
        patBusy.value = true
        lifecycleScope.launch {
            try {
                GitHubClient.setToken(token)
                val res = try {
                    GitHubClient.api.me()
                } catch (e: Exception) {
                    GitHubClient.setToken(null)
                    loginError.value = "Could not reach GitHub: ${e.message ?: "network error"}"
                    return@launch
                }
                if (res.isSuccessful) {
                    val login = res.body()?.login ?: "user"
                    tokenStore.saveToken(token, login)
                    loggedIn.value = true
                    // Measures the token instead of assuming it: a token that can read but not
                    // write is the single most common cause of "403 on every upload", and the
                    // user learns that here rather than after the first failed push.
                    vm.refreshAccess()
                } else {
                    GitHubClient.setToken(null)
                    val failure = res.toGitHubFailure("GitHub rejected this token")
                    loginError.value = when (failure.kind) {
                        FailureKind.TokenInvalid ->
                            "That token was rejected - it has expired, been revoked, or belongs " +
                                "to another host. ${failure.fix}"
                        else -> "Token sign-in failed: ${failure.render()}. ${failure.fix}"
                    }
                }
            } finally {
                patBusy.value = false
            }
        }
    }

    /**
     * PKCE redirect lands here: githubdesktop://callback?code=...&state=...
     * We verify the nonce, then exchange code + in-memory code_verifier for a token.
     */
    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "githubdesktop" || data.host != "callback") return

        // Consume it: leaving the data on the intent would replay the exchange on
        // every recreate (rotation, process death, back-and-forth from the browser).
        intent.data = null

        data.getQueryParameter("error")?.let { param ->
            loginError.value = data.getQueryParameter("error_description")
                ?: "Sign-in was not completed ($param)."
            OAuthManager.cancel()
            return
        }

        val code = data.getQueryParameter("code")
        if (code == null) {
            loginError.value = "Sign-in was not completed."
            OAuthManager.cancel()
            return
        }
        val returnedState = data.getQueryParameter("state")

        exchangingCode.value = true
        loginError.value = null
        lifecycleScope.launch {
            try {
                val token = OAuthManager.exchangeCode(code, returnedState)
                GitHubClient.setToken(token)
                val login = runCatching { GitHubClient.api.me().body()?.login }.getOrNull()
                tokenStore.saveToken(token, login ?: "user")
                loggedIn.value = true
                vm.refreshAccess()
            } catch (e: OAuthManager.LoginException) {
                loginError.value = e.message
            } catch (e: Exception) {
                loginError.value = e.message ?: "Sign-in failed."
            } finally {
                exchangingCode.value = false
            }
        }
    }
}
