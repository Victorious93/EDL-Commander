plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.victorious93.edlcommander"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.victorious93.edlcommander"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-scaffold"
    }

    // Fleet signing: the companion and Droidcommand-AI must be signed with the same key for the
    // signature-level bind permission to be granted. The key is never in the repo; supply it via
    // environment variables. Without them, builds use the per-machine debug key, which will NOT
    // match Droidcommand (see docs/ANDROID.md, "Same-signing").
    val fleetKeystore = System.getenv("EDL_FLEET_KEYSTORE")
    if (!fleetKeystore.isNullOrBlank()) {
        signingConfigs {
            create("fleet") {
                storeFile = file(fleetKeystore)
                storePassword = System.getenv("EDL_FLEET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EDL_FLEET_KEY_ALIAS")
                keyPassword = System.getenv("EDL_FLEET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("fleet")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("fleet")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
