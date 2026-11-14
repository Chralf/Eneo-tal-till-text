import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val cacheBuildDir = file("${System.getProperty("user.home")}/Library/Caches/se.iteca.eneotaltilltext/app/build")
layout.buildDirectory.set(cacheBuildDir)
redirectLocalBuildDirToCache(cacheBuildDir)

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "se.iteca.eneotaltilltext"
    compileSdk = 37

    defaultConfig {
        applicationId = "se.iteca.eneotaltilltext"
        minSdk = 33
        targetSdk = 37
        versionCode = 25
        versionName = "2.13"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.configureEach {
    val usesClassOutputs = name.contains("dex", ignoreCase = true) ||
        name.contains("kotlin", ignoreCase = true) ||
        name.contains("package", ignoreCase = true)
    if (usesClassOutputs) {
        doFirst {
            redirectLocalBuildDirToCache(cacheBuildDir)
            deleteFinderDuplicates(cacheBuildDir)
            deleteFinderDuplicates(file("build"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

fun redirectLocalBuildDirToCache(cacheDir: File) {
    cacheDir.mkdirs()
    val local = file("build")
    val localPath = local.toPath()
    if (Files.isSymbolicLink(localPath)) {
        val currentTarget = Files.readSymbolicLink(localPath).toAbsolutePath().normalize()
        if (currentTarget == cacheDir.toPath().toAbsolutePath().normalize()) return
        Files.delete(localPath)
    } else if (local.exists()) {
        local.deleteRecursively()
    }
    Files.createSymbolicLink(localPath, cacheDir.toPath())
}

fun deleteFinderDuplicates(dir: File) {
    if (!dir.exists()) return
    dir.walkTopDown()
        .filter { it.name.contains(" 2.") || it.name.endsWith(" 2") }
        .forEach { it.deleteRecursively() }
}
