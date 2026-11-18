plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.tuba.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tuba"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
}
