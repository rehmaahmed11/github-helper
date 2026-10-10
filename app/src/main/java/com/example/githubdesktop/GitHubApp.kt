package com.example.githubdesktop

import android.app.Application
import com.example.githubdesktop.auth.TokenStore

class GitHubApp : Application() {
    lateinit var tokenStore: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
    }
}
