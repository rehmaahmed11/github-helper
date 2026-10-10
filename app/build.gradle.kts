// Must be imported: inside a Gradle Kotlin DSL script, `java` resolves to the
// JavaPluginExtension (Project.java), so `java.util.Properties` does not resolve.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * OAuth Client ID for the GitHub OAuth App (PKCE flow).
 *
 * It is a public identifier, not a secret - but it identifies ONE specific OAuth
 * App on GitHub, so it is kept out of version control. Resolved from, in order:
 *
 *   1. a Gradle property: `-PGITHUB_CLIENT_ID=...` (also accepts `github.clientId`)
 *   2. environment variable `GITHUB_CLIENT_ID`
 *   3. `github.clientId` in local.properties (git-ignored)
 *
 * See README.md. CI has none of these, so we fall back to a placeholder rather
 * than failing the build; the app then shows a "not configured" message at login.
 */
val oauthClientId: String = run {
    fun gradleProp(vararg names: String): String? =
        names.firstNotNullOfOrNull { findProperty(it) as? String }

    val fromLocalProperties = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }
                .getProperty("github.clientId")
        }

    val id = gradleProp("github.clientId", "github.client.id", "GITHUB_CLIENT_ID")
        ?: System.getenv("GITHUB_CLIENT_ID")
        ?: fromLocalProperties
        ?: ""

    if (id.isBlank()) {
        // println rather than logger.warn: always available, and CI shows it in the log.
        println(
            "WARNING: GITHUB_CLIENT_ID is not set - sign-in will be disabled in this build. " +
                "Set github.clientId in local.properties, export GITHUB_CLIENT_ID, or pass " +
                "-PGITHUB_CLIENT_ID=<id>. See README.md."
        )
    }
    id.ifBlank { "YOUR_CLIENT_ID" }
}

android {
    namespace = "com.example.githubdesktop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.githubdesktop"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Used by Custom Tabs to route back into the app.
        manifestPlaceholders["appAuthRedirectScheme"] = "githubdesktop"

        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "GITHUB_REDIRECT_URI", "\"githubdesktop://callback\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
