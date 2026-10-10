package com.example.githubdesktop.ui.components

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.githubdesktop.data.RepoRepository
import com.example.githubdesktop.data.api.githubError
import com.example.githubdesktop.data.model.ContentItem
import com.example.githubdesktop.ui.util.OpResult
import com.example.githubdesktop.ui.util.PushOp
import com.example.githubdesktop.ui.util.SafHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads a file, lets the user change it, and writes it back as one commit on a fresh branch.
 *
 * The write goes through [SafHelper.push] (tree + commit + ref) rather than
 * `PUT /contents/{path}`: the Contents API demands the blob sha of the version being replaced and
 * rejects the write without it, which is what made re-saving a file fail with 422.
 */
@Composable
fun FileEditorDialog(
    owner: String,
    repo: String,
    file: ContentItem,
    branch: String,
    onDismiss: () -> Unit,
    onSaved: (workingBranch: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val r = remember { RepoRepository() }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // A "create new file" placeholder carries no sha and has nothing to read.
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
            val inline = body?.content
            when {
                body == null -> error = "Could not read `${file.path}`."
                // GitHub omits inline content above ~1 MB (encoding "none"); saving then would
                // replace a real file with an empty one, so the file stays untouched.
                body.encoding != "base64" || inline.isNullOrBlank() && body.size > 0 ->
                    error = "`${file.path}` is ${body.size} bytes - too large to edit here " +
                        "(GitHub only returns small files inline)."
                else -> {
                    val decoded = runCatching {
                        String(Base64.decode(inline.orEmpty().replace("\n", ""), Base64.DEFAULT))
                    }.getOrNull()
                    if (decoded == null) {
                        error = "`${file.path}` is not text, so it cannot be edited here. " +
                            "Upload a replacement instead."
                    } else {
                        content = decoded
                    }
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
                        val result = withContext(Dispatchers.IO) {
                            SafHelper.push(
                                r, owner, repo, working, branch,
                                listOf(PushOp.Add(file.path, content.toByteArray())),
                                if (isNewFile) "Add ${file.path} via mobile"
                                else "Edit ${file.path} via mobile"
                            )
                        }
                        saving = false
                        when (result) {
                            is OpResult.Success -> onSaved(result.workingBranch)
                            is OpResult.Failure -> error = result.message
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
