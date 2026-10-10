package com.example.githubdesktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
fun BranchMergeDialog(
    workingBranch: String,
    mainBranch: String,
    onDismiss: () -> Unit,
    onConfirm: (merge: Boolean, deleteAfter: Boolean) -> Unit
) {
    var merge by remember { mutableStateOf(true) }
    var deleteAfter by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push complete") },
        text = {
            Column {
                Text("Branch `$workingBranch` was pushed.")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = merge, onCheckedChange = { merge = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Merge into `$mainBranch`")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteAfter, onCheckedChange = { deleteAfter = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Delete `$workingBranch` afterwards")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(merge, deleteAfter) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep branch") }
        }
    )
}
