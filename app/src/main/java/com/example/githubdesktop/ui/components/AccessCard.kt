package com.example.githubdesktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.githubdesktop.data.AccessReport
import com.example.githubdesktop.data.api.FailureKind
import com.example.githubdesktop.ui.AppViewModel

/**
 * Says, in plain words, what the stored credential may and may not do - and opens the exact
 * GitHub page that fixes it.
 *
 * [AccessBanner] stays invisible while everything is fine and appears the moment GitHub has
 * refused something for a reason the user can act on. That is the difference between "403,
 * nothing works" and "this token has no `repo` scope; tap to make one".
 */
@Composable
fun AccessBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val report by vm.access.collectAsState()
    val checking by vm.checkingAccess.collectAsState()
    val data = report ?: return

    val message: String? = when {
        data.failure != null -> "This sign-in does not work: ${data.failure.render()}"
        data.createBlocked -> "Creating repositories is blocked for this sign-in. " +
            (data.advice ?: data.blockedReason.orEmpty())
        data.coreRemaining != null && data.coreRemaining <= 30 ->
            "GitHub's API budget for this device is nearly used up (${data.coreRemaining} calls " +
                "left this hour), so writes may be refused for a while."
        else -> null
    }
    if (message.isNullOrBlank()) return

    val urgent = data.failure != null || data.createBlocked
    val container = if (urgent) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.tertiaryContainer
    val onContainer = if (urgent) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onTertiaryContainer
    val uri = LocalUriHandler.current

    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (urgent) "Action needed" else "Heads up",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (urgent) {
                    Button(onClick = { runCatching { uri.openUri(AccessReport.CLASSIC_TOKEN_URL) } }) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Create the right token")
                    }
                }
                TextButton(onClick = { vm.refreshAccess() }, enabled = !checking) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = onContainer
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(if (checking) "Checking…" else "Re-check", color = onContainer)
                }
            }
        }
    }
}

/**
 * The full read-out: credential type, its scopes, which operations GitHub allows, the rate-limit
 * budget, and the app's structured version of the last failure - including the request id, which
 * is what GitHub support asks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val report by vm.access.collectAsState()
    val checking by vm.checkingAccess.collectAsState()
    val lastFailure by vm.lastFailure.collectAsState()
    val uri = LocalUriHandler.current
    val data = report

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Access check", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (data == null) "Not checked yet - GitHub has not been asked."
                else "Asked GitHub ${ago(data.checkedAt)}. Every line below is GitHub's own answer.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshAccess() }, enabled = !checking) {
                    Text(if (checking) "Checking…" else "Re-check now")
                }
                OutlinedButton(onClick = { runCatching { uri.openUri(AccessReport.CLASSIC_TOKEN_URL) } }) {
                    Text("Classic token")
                }
                OutlinedButton(onClick = { runCatching { uri.openUri(AccessReport.FINE_GRAINED_TOKEN_URL) } }) {
                    Text("Fine-grained")
                }
            }

            if (data != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text(data.login ?: "unknown account", fontWeight = FontWeight.SemiBold)
                Text(data.kind.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    data.kind.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Scopes: " + if (data.grantedScopes.isEmpty()) "none reported"
                        else data.grantedScopes.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (data.grantedScopes.isNotEmpty() && !data.grantedScopes.contains("repo")) {
                    Text(
                        "`repo` is missing - that is why browsing works and writing does not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(10.dp))
                Capability("Read your repositories", data.canReadRepos)
                Capability("Create repositories", data.canCreateRepos)
                Capability(
                    "Delete repositories",
                    if (data.grantedScopes.isNotEmpty()) data.canDeleteRepos else null
                )
                ListItem(
                    headlineContent = { Text("Write files") },
                    supportingContent = {
                        Text(
                            "Checked per repository by its push permission. Every write lands on a " +
                                "branch you approve, never straight onto your default branch."
                        )
                    }
                )
                data.blockedReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(rateLine(data), style = MaterialTheme.typography.bodySmall)
            }

            lastFailure?.let { failure ->
                Spacer(Modifier.height(12.dp))
                Text("Last failure", style = MaterialTheme.typography.titleSmall)
                Text(failure.renderLong(), style = MaterialTheme.typography.bodySmall)
                if (failure.kind == FailureKind.RateLimited || failure.kind == FailureKind.Server) {
                    Text(
                        "The app already waited and retried this one for as long as GitHub asked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Capability(label: String, allowed: Boolean?) {
    val (mark, color) = when (allowed) {
        true -> "✓" to MaterialTheme.colorScheme.primary
        false -> "✗" to MaterialTheme.colorScheme.error
        null -> "?" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun rateLine(data: AccessReport): String {
    val left = data.coreRemaining?.toString() ?: "?"
    val total = data.coreLimit?.toString() ?: "?"
    val reset = data.coreResetEpochSeconds?.let { ", refills ${secondsUntil(it)}" } ?: ""
    return "API budget: $left of $total calls left this hour$reset"
}

private fun ago(epochMillis: Long): String {
    val seconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} h ago"
    }
}

private fun secondsUntil(epochSeconds: Long): String {
    val left = epochSeconds - System.currentTimeMillis() / 1000
    if (left <= 0) return "now"
    val minutes = left / 60
    return if (minutes > 0) "in $minutes min" else "in ${left % 60} s"
}
