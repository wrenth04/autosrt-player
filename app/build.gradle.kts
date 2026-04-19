plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val debugKeystorePath = providers.gradleProperty("DEBUG_KEYSTORE_PATH")
    .orElse(providers.environmentVariable("DEBUG_KEYSTORE_PATH"))
    .orElse(layout.projectDirectory.file("../debug.keystore").asFile.absolutePath)
    .get()
val debugKeystoreFile = file(debugKeystorePath)
val debugStorePassword = providers.gradleProperty("DEBUG_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD"))
    .orElse("android")
    .get()
val debugKeyAlias = providers.gradleProperty("DEBUG_KEY_ALIAS")
    .orElse(providers.environmentVariable("DEBUG_KEY_ALIAS"))
    .orElse("androiddebugkey")
    .get()
val debugKeyPassword = providers.gradleProperty("DEBUG_KEY_PASSWORD")
    .orElse(providers.environmentVariable("DEBUG_KEY_PASSWORD"))
    .orElse("android")
    .get()

android {
    namespace = "com.example.autosrtplayer"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            if (!debugKeystoreFile.exists()) {
                throw GradleException("Debug keystore not found at ${debugKeystoreFile.absolutePath}. Set DEBUG_KEYSTORE_PATH or restore debug.keystore before building.")
            }
            storeFile = debugKeystoreFile
            storePassword = debugStorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
    }

    defaultConfig {
        applicationId = "com.example.autosrtplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
