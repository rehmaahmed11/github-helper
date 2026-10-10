package com.example.githubdesktop.ui.components

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.ui.util.OpResult
import com.example.githubdesktop.ui.util.SafHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileEditorDialog(
    owner: String,
    repo: String,
    file: ContentItem,
    branch: String,
    baseBranch: String,
    onDismiss: () -> Unit,
    onSaved: (workingBranch: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val r = remember { RepoRepository() }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A "create new file" placeholder carries no sha.
    val isNewFile = file.sha.isEmpty()

    LaunchedEffect(file.path, file.sha) {
        loading = true
        error = null
        if (isNewFile) {
            loading = false
            return@LaunchedEffect
        }

        val resp = withContext(Dispatchers.IO) { r.getFile(owner, repo, file.path, branch) }
        if (!resp.isSuccessful) {
            error = "Could not open `${file.path}`: ${resp.githubError()}"
        } else {
            val body = resp.body()
            if (body == null) {
                error = "Could not read `${file.path}`."
            } else if (body.encoding != "base64" || (body.content.isBlank() && file.size > 0)) {
                // GitHub omits inline content for blobs over ~1 MB (encoding "none").
                // Saving here would replace a real file with an empty one.
                error = "`${file.path}` is too large to edit in the app " +
                    "(GitHub only returns small files inline). It was left untouched."
            } else {
                val decoded = runCatching {
                    String(Base64.decode(body.content.replace("\n", ""), Base64.DEFAULT))
                }.getOrNull()
                if (decoded == null) {
                    error = "`${file.path}` is not valid text and cannot be edited here."
                } else {
                    content = decoded
                }
            }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNewFile) "New ${file.path}" else "Edit ${file.path}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    enabled = !loading && error == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    label = { Text("Content") }
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                // Never offer Save over content we failed to load.
                enabled = !loading && !saving && error == null,
                onClick = {
                    scope.launch {
                        saving = true
                        error = null

                        val working = SafHelper.newWorkingBranch()
                        val started = SafHelper.createWorkingBranch(r, owner, repo, working, baseBranch)
                        if (started is OpResult.Failure) {
                            error = started.message
                            saving = false
                            return@launch
                        }

                        val result = SafHelper.putFile(
                            r, owner, repo, file.path,
                            content.toByteArray(),
                            file.sha.ifEmpty { null },
                            working,
                            if (isNewFile) "Add ${file.path} via mobile"
                            else "Edit ${file.path} via mobile"
                        )
                        saving = false

                        when (result) {
                            is OpResult.Success -> onSaved(working)
                            is OpResult.Failure -> {
                                error = result.message
                                // Nothing landed, so don't leave the branch behind.
                                runCatching { r.deleteBranch(owner, repo, working) }
                            }
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}