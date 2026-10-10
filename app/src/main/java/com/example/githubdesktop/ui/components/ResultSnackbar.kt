package com.example.githubdesktop.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.githubdesktop.ui.AppViewModel

/**
 * A [SnackbarHostState] that shows whatever the ViewModel most recently put in
 * `error` / `notice`, then clears it.
 *
 * The ViewModel was already recording failures - nothing ever read them, so a
 * 422 or a dropped upload looked like a silent no-op.
 */
@Composable
fun rememberResultSnackbarHost(vm: AppViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(message)
        vm.clearError()
    }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(message)
        vm.clearNotice()
    }

    return host
}