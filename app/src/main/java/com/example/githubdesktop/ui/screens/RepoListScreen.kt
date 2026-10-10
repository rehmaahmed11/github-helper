package com.example.githubdesktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.githubdesktop.data.model.Repo
import com.example.githubdesktop.ui.AppViewModel
import com.example.githubdesktop.ui.components.AccessBanner
import com.example.githubdesktop.ui.components.AccessSheet
import com.example.githubdesktop.ui.components.rememberResultSnackbarHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    vm: AppViewModel,
    login: String,
    onOpenRepo: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    val repos by vm.repos.collectAsState()
    val loading by vm.loading.collectAsState()
    val creating by vm.creating.collectAsState()
    val createError by vm.createError.collectAsState()
    val snackbar = rememberResultSnackbarHost(vm)
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var showAccess by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Repo?>(null) }

    val filtered = repos.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Repos · $login") },
                actions = {
                    IconButton(onClick = { vm.loadUserAndRepos() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showAccess = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Access check")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, null)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search repos") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // "Create repo does nothing" is nearly always a token without the repo scope;
            // say it here, before the user has to discover it in a dialog.
            AccessBanner(vm, Modifier.padding(horizontal = 12.dp))
            if (filtered.isEmpty() && !loading) {
                Text(
                    if (repos.isEmpty()) "No repositories yet. Tap + to make one, or refresh " +
                        "with the button above."
                    else "Nothing matches "$query".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, fontWeight = FontWeight.SemiBold)
                                if (r.private) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Lock, null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        supportingContent = { Text(r.description ?: "No description") },
                        trailingContent = {
                            IconButton(onClick = { toDelete = r }) {
                                Icon(Icons.Filled.Delete, null)
                            }
                        },
                        modifier = Modifier.clickable { onOpenRepo(r.owner.login, r.name) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        CreateRepoDialog(
            busy = creating,
            error = createError,
            onDismiss = {
                if (!creating) {
                    vm.clearCreateError()
                    showCreate = false
                }
            },
            onCreate = { name, desc, priv ->
                vm.createRepo(name, desc, priv) { showCreate = false }
            }
        )
    }

    if (showAccess) {
        AccessSheet(vm, onDismiss = { showAccess = false })
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete repository") },
            text = { Text("Delete ${r.fullName}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRepo(r.owner.login, r.name) { toDelete = null }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** GitHub repo names: letters, digits, '-', '_', '.'; no spaces. */
private val REPO_NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

@Composable
fun CreateRepoDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, String?, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(false) }

    // Spaces are not allowed in repo names; turn them into '-' as the user types.
    val cleanName = name.trim()
    val nameValid = REPO_NAME.matches(cleanName)
    val nameHint = when {
        cleanName.isEmpty() -> "Letters, numbers, - _ . (spaces become -)"
        !nameValid -> "Only letters, numbers, - _ . are allowed (max 100)"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace(' ', '-') },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = cleanName.isNotEmpty() && !nameValid,
                    supportingText = { nameHint?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = priv,
                        onCheckedChange = { priv = it },
                        enabled = !busy
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (priv) "Private" else "Public")
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("Creating repository…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameValid && !busy,
                onClick = {
                    onCreate(cleanName, desc.trim().ifBlank { null }, priv)
                }
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
