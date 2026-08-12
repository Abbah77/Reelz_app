plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace   = "com.axio.reelz"
    compileSdk  = 35

    defaultConfig {
        applicationId  = "com.axio.reelz"
        minSdk         = 26
        targetSdk      = 35
        versionCode    = 3
        versionName    = "3.0.0"

        // ── Backend URL — set this to your VPS URL ──────────────────────────
        // Change via build variant or CI/CD env var.
        // The app fetches its own config from this URL on first launch,
        // which can then update the URL itself for zero-downtime migrations.
        buildConfigField(
            "String", "BACKEND_URL",
            "\"${System.getenv("REELZ_BACKEND_URL") ?: "https://your-vps.example.com"}\""
        )
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("CM_KEYSTORE_PATH")
            if (keystoreFile != null) {
                storeFile      = file(keystoreFile)
                storePassword  = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias       = System.getenv("CM_KEY_ALIAS")
                keyPassword    = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable    = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.work.runtime)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.androidx)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource)
    implementation(libs.media3.transformer)
    implementation("androidx.media3:media3-exoplayer-workmanager:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")

    // Database (smart cache — lean schema)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network — modern Retrofit stack
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Image
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Async
    implementation(libs.coroutines.android)

    // Storage / Prefs
    implementation(libs.datastore)
    implementation(libs.gson)
    implementation(libs.palette)

    // Auth
    implementation(libs.google.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)

    // Permissions
    implementation(libs.accompanist.permissions)

    // Ads — AppLovin MAX + IMA
    implementation("com.applovin:applovin-sdk:12.5.0")
    implementation("com.google.ads.interactivemedia.v3:interactivemedia:3.33.0")
    implementation("androidx.media3:media3-exoplayer-ima:1.3.1")

    // QR code (transfer screen)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Nearby Connections (transfer feature)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    debugImplementation(libs.compose.ui.tooling)
}
