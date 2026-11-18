import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Credenciais do Supabase vêm do local.properties (fora do controle de versão).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val generateTubaConfig by tasks.registering {
    val url = localProperties.getProperty("supabase.url", "").trim()
    val anonKey = localProperties.getProperty("supabase.anonKey", "").trim()
    val outputDir = layout.buildDirectory.dir("generated/tubaConfig/kotlin")
    inputs.property("url", url)
    inputs.property("anonKey", anonKey)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/tuba/TubaConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.tuba
            |
            |internal object TubaConfig {
            |    const val SUPABASE_URL = "$url"
            |    const val SUPABASE_ANON_KEY = "$anonKey"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    android {
        namespace = "com.tuba.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateTubaConfig)
            dependencies {
                api(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.material3)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.navigationevent.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(project.dependencies.platform(libs.supabase.bom))
                implementation(libs.supabase.postgrest)
                implementation(libs.supabase.auth)
                implementation(libs.maplibre.compose)
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // O MapLibre exige escolher o renderizador nativo no Android.
            implementation(libs.maplibre.runtime.opengl)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
