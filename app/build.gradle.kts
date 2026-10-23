import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// The repo secret ADMIN_TOKEN is now only the SYNC key: the contacts worker lets it upload, never search or read.
// Admin reads need the owner's password (worker POST /admin/login), which is never in the APK.
val syncToken: String = System.getenv("ADMIN_TOKEN") ?: localProps.getProperty("adminToken") ?: ""
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER") ?: localProps.getProperty("buildNumber") ?: "178").toInt()
val workerUrl: String = System.getenv("HEIMISH_WORKER_URL") ?: localProps.getProperty("workerUrl") ?: ""

android {
    namespace = "com.heimish.messages"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.heimish.messages"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "3.6.$buildNumber"  // the build is part of the visible version (updates compare BUILD_NUMBER / the -bN tag, not this)

        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "SYNC_TOKEN", "\"$syncToken\"")
        buildConfigField("String", "WORKER_URL", "\"$workerUrl\"")
    }

    val ksPath = System.getenv("KEYSTORE_PATH")
    if (ksPath != null && file(ksPath).exists()) {
        signingConfigs.getByName("debug") {
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    // The APK CI publishes is the "debug" variant signed with the release key (the updater downloads app-debug.apk,
    // so the variant name stays). On CI it is built like a release: not debuggable (Compose runs many times slower in a
    // debuggable app and skips its baseline profiles) and optimized with R8. Same package + same key = normal update.
    val optimized = System.getenv("CI") == "true" || (localProps.getProperty("optimizedDebug") == "true")
    buildTypes {
        debug {
            if (optimized) {
                isDebuggable = false
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        // QA variant: production debug plus the QA-only fixture screen (src/qa). The public
        // release stays the plain debug APK; the emulator gate runs its suite on app-qa.apk
        // and smoke-checks the exact public artifact at the end.
        create("qa") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("io.coil-kt:coil-compose:2.5.0")
    // In-app video player and voice-note fallback (own extractors for AMR / 3GP / MP4, independent of the phone's)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Installs the baseline profile (src/main/baseline-prof.txt + the Compose libraries' own) on sideloaded installs
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    testImplementation("junit:junit:4.13.2")
}
// v3.4
