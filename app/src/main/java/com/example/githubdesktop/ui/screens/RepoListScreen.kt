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
    val snackbar = rememberResultSnackbarHost(vm)
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
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
                        Icon(Icons.Filled.Refresh, null)
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
            onDismiss = { showCreate = false },
            onCreate = { name, desc, priv ->
                vm.createRepo(name, desc, priv) { showCreate = false }
            }
        )
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

@Composable
fun CreateRepoDialog(onDismiss: () -> Unit, onCreate: (String, String?, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = priv, onCheckedChange = { priv = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Private")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), desc.trim().ifBlank { null }, priv) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
