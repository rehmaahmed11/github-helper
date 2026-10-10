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
import com.example.githubdesktop.data.api.GitHubClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = (application as GitHubApp).tokenStore

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
                    configured = OAuthManager.isConfigured,
                    busy = exchangingCode.value,
                    errorMessage = loginError.value,
                    onLogin = ::startLogin
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