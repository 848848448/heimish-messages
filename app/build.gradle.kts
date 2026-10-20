import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val adminToken: String = System.getenv("ADMIN_TOKEN") ?: localProps.getProperty("adminToken") ?: ""
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
        versionName = "3.6"

        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "ADMIN_TOKEN", "\"$adminToken\"")
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
// v3.4
