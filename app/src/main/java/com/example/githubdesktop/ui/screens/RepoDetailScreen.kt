package com.example.githubdesktop.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import com.example.githubdesktop.GitHubApp
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.ui.AppViewModel
import com.example.githubdesktop.ui.components.BranchMergeDialog
import com.example.githubdesktop.ui.components.FileEditorDialog
import com.example.githubdesktop.ui.components.rememberResultSnackbarHost
import com.example.githubdesktop.ui.util.Cloner
import com.example.githubdesktop.ui.util.FolderUploader
import com.example.githubdesktop.ui.util.OpResult
import com.example.githubdesktop.ui.util.SafHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RepoDetailScreen(
    vm: AppViewModel,
    owner: String,
    name: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = remember { RepoRepository() }
    val contents by vm.contents.collectAsState()
    val loading by vm.loading.collectAsState()
    val repos by vm.repos.collectAsState()
    val branches by vm.branches.collectAsState()
    val snackbar = rememberResultSnackbarHost(vm)

    // The default branch is not always "main" - read it from the repo instead of
    // assuming, otherwise every write fails on a `master` repo.
    val repoMeta = repos.firstOrNull { it.name == name }
    val baseBranch = repoMeta?.defaultBranch ?: "main"

    var currentPath by remember { mutableStateOf("") }
    var currentBranch by remember(baseBranch) { mutableStateOf(baseBranch) }
    var showUploadMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ContentItem?>(null) }
    var deleting by remember { mutableStateOf<ContentItem?>(null) }
    var showMergeDialog by remember { mutableStateOf<String?>(null) }
    var showBranchMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath, currentBranch) {
        vm.loadContents(owner, name, currentPath, currentBranch)
    }
    LaunchedEffect(owner, name) { vm.loadBranches(owner, name) }

    /** Report the outcome and only offer the merge step if something landed. */
    fun report(result: OpResult, working: String?) {
        when (result) {
            is OpResult.Success -> {
                vm.reportNotice(result.message)
                if (working != null) showMergeDialog = working
            }
            is OpResult.Failure -> vm.reportError(result.message)
        }
    }

    val uploadFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val working = SafHelper.newWorkingBranch()
                report(
                    SafHelper.pushFiles(ctx, r, owner, name, currentPath, uris, working, baseBranch),
                    working
                )
            }
        }
    }

    val uploadFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Keep access past this screen (config change, process death) mid-upload.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                val working = SafHelper.newWorkingBranch()
                report(
                    FolderUploader.uploadTree(
                        ctx, r, owner, name, currentPath, it, working, baseBranch
                    ),
                    working
                )
            }
        }
    }

    val cloneFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                val token = (ctx.applicationContext as GitHubApp).tokenStore.getToken()
                if (token == null) {
                    vm.reportError("Your sign-in expired. Sign in again.")
                } else {
                    report(Cloner.downloadAndExtract(ctx, token, owner, name, it), null)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "branch: $currentBranch · /$currentPath",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.isEmpty()) onBack()
                        else currentPath = currentPath.substringBeforeLast('/', "")
                    }) { Icon(Icons.Filled.ArrowBack, null) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showBranchMenu = true }) {
                            Icon(Icons.Filled.AccountTree, contentDescription = "Switch branch")
                        }
                        DropdownMenu(
                            expanded = showBranchMenu,
                            onDismissRequest = { showBranchMenu = false }
                        ) {
                            if (branches.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No branches loaded") },
                                    onClick = { showBranchMenu = false }
                                )
                            }
                            branches.forEach { branch ->
                                DropdownMenuItem(
                                    text = { Text(branch.name) },
                                    onClick = {
                                        showBranchMenu = false
                                        // Paths do not carry across branches.
                                        currentBranch = branch.name
                                        currentPath = ""
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { cloneFolder.launch(null) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Clone")
                    }
                    IconButton(
                        enabled = repoMeta != null,
                        onClick = { vm.setVisibility(owner, name, !(repoMeta?.private ?: false)) }
                    ) {
                        Icon(
                            if (repoMeta?.private == true) Icons.Filled.Lock
                            else Icons.Filled.LockOpen,
                            contentDescription = "Toggle visibility"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showUploadMenu = true }) {
                Icon(Icons.Filled.Add, null)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(Modifier.fillMaxSize()) {
                items(contents, key = { it.sha + it.path }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        leadingContent = {
                            Icon(
                                if (item.type == "dir") Icons.Filled.Folder
                                else Icons.Filled.Description,
                                null
                            )
                        },
                        supportingContent = {
                            if (item.type == "file") Text("${item.size} bytes")
                            else Text("folder")
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (item.type == "dir") currentPath = item.path
                                else editing = item
                            },
                            onLongClick = {
                                if (item.type == "file") deleting = item
                            }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showUploadMenu) {
        ModalBottomSheet(onDismissRequest = { showUploadMenu = false }) {
            ListItem(
                headlineContent = { Text("Upload files") },
                leadingContent = { Icon(Icons.Filled.UploadFile, null) },
                modifier = Modifier.clickable {
                    showUploadMenu = false
                    uploadFiles.launch(arrayOf("*/*"))
                }
            )
            ListItem(
                headlineContent = { Text("Upload folder") },
                leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                modifier = Modifier.clickable {
                    showUploadMenu = false
                    uploadFolder.launch(null)
                }
            )
            ListItem(
                headlineContent = { Text("Create new file") },
                leadingContent = { Icon(Icons.Filled.NoteAdd, null) },
                modifier = Modifier.clickable {
                    showUploadMenu = false
                    val name = "new-file.txt"
                    val path = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    editing = ContentItem(
                        name = name, path = path, sha = "",
                        size = 0, type = "file", downloadUrl = null
                    )
                }
            )
        }
    }

    editing?.let { file ->
        FileEditorDialog(
            owner = owner, repo = name, file = file,
            branch = currentBranch,
            baseBranch = baseBranch,
            onDismiss = { editing = null },
            onSaved = { working ->
                editing = null
                vm.reportNotice("Saved on `$working`.")
                showMergeDialog = working
            }
        )
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete file") },
            text = { Text("Delete ${file.path}?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val working = SafHelper.newWorkingBranch()
                        report(
                            SafHelper.deleteViaBranch(
                                r, owner, name, file.path, file.sha, working, currentBranch
                            ),
                            working
                        )
                        deleting = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }

    showMergeDialog?.let { working ->
        BranchMergeDialog(
            workingBranch = working,
            mainBranch = baseBranch,
            onDismiss = { showMergeDialog = null },
            onConfirm = { merge, deleteAfter ->
                scope.launch {
                    if (merge) {
                        when (val merged = SafHelper.mergeBranch(r, owner, name, baseBranch, working)) {
                            is OpResult.Success -> vm.reportNotice(merged.message)
                            is OpResult.Failure -> vm.reportError(merged.message)
                        }
                    }
                    if (deleteAfter) {
                        when (val deleted = SafHelper.deleteBranch(r, owner, name, working)) {
                            is OpResult.Success -> vm.reportNotice(deleted.message)
                            is OpResult.Failure -> vm.reportError(deleted.message)
                        }
                    }
                    showMergeDialog = null
                    vm.loadContents(owner, name, currentPath, currentBranch)
                }
            }
        )
    }
}