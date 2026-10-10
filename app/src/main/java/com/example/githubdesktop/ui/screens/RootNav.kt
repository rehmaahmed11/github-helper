package com.example.githubdesktop.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.githubdesktop.ui.AppViewModel

@Composable
fun RootNav(vm: AppViewModel, login: String, onLogout: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "repos") {
        composable("repos") {
            RepoListScreen(
                vm = vm,
                login = login,
                onOpenRepo = { owner, name -> nav.navigate("repo/$owner/$name") },
                onLogout = onLogout
            )
        }
        composable("repo/{owner}/{name}") { back ->
            val owner = back.arguments?.getString("owner")!!
            val name = back.arguments?.getString("name")!!
            RepoDetailScreen(
                vm = vm,
                owner = owner,
                name = name,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
