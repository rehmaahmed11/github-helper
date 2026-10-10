package com.example.githubdesktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    configured: Boolean,
    oauthBusy: Boolean,
    patBusy: Boolean,
    errorMessage: String?,
    savedClientId: String,
    onLogin: () -> Unit,
    onPatLogin: (String) -> Unit,
    onSaveOAuth: (String, String) -> Unit
) {
    var showOAuthForm by remember(configured) { mutableStateOf(!configured) }
    var clientId by remember(savedClientId) { mutableStateOf(savedClientId) }
    var clientSecret by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    var secretVisible by remember { mutableStateOf(false) }

    val busy = oauthBusy || patBusy

    Scaffold(
        topBar = { TopAppBar(title = { Text("GitHub Desktop") }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Sign in with GitHub",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Each person signs in with their own GitHub account.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            // ---- Option 1: browser / OAuth ----
            Button(
                onClick = onLogin,
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (oauthBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (oauthBusy) "Signing in…" else "Continue with GitHub")
            }

            if (!configured) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Browser sign-in needs your OAuth App credentials once. " +
                        "Enter them below, or use a personal access token instead — " +
                        "no rebuild needed either way.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            // OAuth credential form: always expandable, auto-expanded when needed.
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showOAuthForm = !showOAuthForm },
                enabled = !busy
            ) {
                Text(if (showOAuthForm) "Hide OAuth setup" else "Set up browser sign-in")
            }

            if (showOAuthForm) {
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("OAuth Client ID") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("OAuth Client Secret") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (secretVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { secretVisible = !secretVisible }) {
                            Text(if (secretVisible) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create these at github.com → Settings → Developer settings → " +
                        "OAuth Apps → New OAuth App, with callback URL " +
                        "githubdesktop://callback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onSaveOAuth(clientId.trim(), clientSecret.trim()) },
                    enabled = !busy && clientId.isNotBlank() && clientSecret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & enable browser sign-in")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "  OR  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))

            // ---- Option 2: personal access token (always works) ----
            Text(
                "Sign in with a personal access token",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pat,
                onValueChange = { pat = it },
                label = { Text("Token (ghp_… or github_pat_…)") },
                singleLine = true,
                enabled = !busy,
                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                visualTransformation = if (patVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { patVisible = !patVisible }) {
                        Text(if (patVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Create one at github.com → Settings → Developer settings → " +
                    "Personal access tokens, with the repo scope.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onPatLogin(pat.trim()) },
                enabled = !busy && pat.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (patBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Verifying…")
                } else {
                    Text("Sign in with token")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
