import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Do not keep app credentials in the tracked build script. Values can be passed
// with -P, environment variables, or the ignored local.properties file.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun configuredCredential(gradleProperty: String, localProperty: String): String =
    providers.gradleProperty(gradleProperty).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable(gradleProperty).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty(localProperty)?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""

fun asBuildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""

val githubClientId = configuredCredential("GITHUB_CLIENT_ID", "github.clientId")
val githubClientSecret = configuredCredential("GITHUB_CLIENT_SECRET", "github.clientSecret")

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

        // GitHub requires both OAuth App credentials when exchanging the code,
        // even when PKCE is used. Defaults are empty so an unconfigured build
        // remains usable for development, but sign-in is disabled at runtime.
        buildConfigField("String", "GITHUB_CLIENT_ID", asBuildConfigString(githubClientId))
        buildConfigField("String", "GITHUB_CLIENT_SECRET", asBuildConfigString(githubClientSecret))
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
