import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    namespace = "com.shezik.drawanywhere"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shezik.drawanywhere"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "2.4.1-hpos-focuspen-diag"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A committed, stable debug key so APKs from successive GitHub Actions runs can be
        // installed over each other. A fresh CI runner would otherwise mint a new random
        // debug key on every build, forcing an uninstall (and settings loss) each time.
        getByName("debug") {
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storeFilePath = localProperties.getProperty("store.file")
            val storePasswordValue = localProperties.getProperty("store.password")
            val keyAliasValue = localProperties.getProperty("key.alias")
            val keyPasswordValue = localProperties.getProperty("key.password")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Disable extra signing block 'Dependency metadata'
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.text)
    implementation(libs.ui)
    implementation(libs.androidx.ui.unit)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.compose.color.picker.android)

    // Transitive dependencies
    androidTestImplementation(libs.androidx.monitor)
    androidTestImplementation(libs.junit)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.ui.geometry)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.core)
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.1")

    // Xiaomi PenEngine SDK (HyperOS stylus). Used only for the touch-film handshake with
    // com.xiaomi.touchservice so Focus Pen barrel gestures reach this app as KeyEvents
    // 194–197 instead of opening the system shortcut wheel. Gesture-to-action mapping is
    // done in-app; the SDK's settings-driven callback path is not used.
    implementation(files("libs/PenEngine-release_0.3.2.aar"))
}

// Disable baseline.prof for reproducibility
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
