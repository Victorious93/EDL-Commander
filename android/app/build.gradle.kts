import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Native core (Rust `edl-jni`, built with cargo-ndk) -------------------------------------
// Needs: Rust toolchain with the Android targets, `cargo install cargo-ndk`, and the NDK pinned
// below. Pass -Pedl.skipNative=true to build the app without the native library (it then reports
// native_core_unavailable for every device command). -Pedl.abis=arm64-v8a,x86_64 overrides the ABIs.
val edlNdkVersion = "27.2.12479018"
val skipNative = providers.gradleProperty("edl.skipNative").orNull == "true"
val nativeAbis = (providers.gradleProperty("edl.abis").orNull ?: "arm64-v8a,armeabi-v7a,x86_64")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
val cargoWorkspace: File = rootProject.projectDir.parentFile

abstract class CargoNdkTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val cargoArgs: ListProperty<String>
    @get:Internal abstract val ndkDir: Property<String>
    @get:Internal abstract val workspaceDir: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val rustSources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        check(File(ndkDir.get()).isDirectory) {
            "Android NDK ${File(ndkDir.get()).name} not found at ${ndkDir.get()}; install it with: sdkmanager \"ndk;${File(ndkDir.get()).name}\""
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val cmd = mutableListOf("cargo", "ndk")
        abis.get().forEach { cmd += listOf("-t", it) }
        cmd += listOf("-o", out.absolutePath, "build")
        cmd += cargoArgs.get()
        execOps.exec {
            workingDir = workspaceDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get())
            commandLine(cmd)
        }
    }
}

val rustSourceFiles = fileTree(cargoWorkspace) {
    include("Cargo.toml", "Cargo.lock", "core/Cargo.toml", "core/src/**", "simulator/Cargo.toml", "simulator/src/**")
    include("android/native/edl-jni/Cargo.toml", "android/native/edl-jni/src/**")
}

android {
    namespace = "io.github.victorious93.edlcommander"
    compileSdk = 35
    ndkVersion = edlNdkVersion

    defaultConfig {
        applicationId = "io.github.victorious93.edlcommander"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-phase3"
        if (!skipNative) ndk { abiFilters += nativeAbis }
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

androidComponents {
    if (!skipNative) {
        onVariants { variant ->
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val build = tasks.register<CargoNdkTask>("buildEdlJni$cap") {
                group = "build"
                description = "Builds libedl_jni.so for ${variant.name} with cargo-ndk"
                abis.set(nativeAbis)
                // Debug builds keep the simulated target (default feature); release builds omit it.
                cargoArgs.set(if (variant.buildType == "release") listOf("--release", "-p", "edl-jni", "--no-default-features") else listOf("--release", "-p", "edl-jni"))
                ndkDir.set(sdkComponents.sdkDirectory.map { it.dir("ndk/$edlNdkVersion").asFile.absolutePath })
                workspaceDir.set(cargoWorkspace)
                rustSources.from(rustSourceFiles)
                outputDir.set(layout.buildDirectory.dir("generated/edlJni/${variant.name}"))
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(build, CargoNdkTask::outputDir)
        }
    }
}

// Host build of the same library so JVM unit tests can exercise the real JNI glue without a device.
val hostLibName = when {
    System.getProperty("os.name").startsWith("Mac") -> "libedl_jni.dylib"
    System.getProperty("os.name").startsWith("Windows") -> "edl_jni.dll"
    else -> "libedl_jni.so"
}
val hostLib = File(System.getenv("CARGO_TARGET_DIR") ?: File(cargoWorkspace, "target").path, "debug/$hostLibName")

val buildEdlJniHost = tasks.register<Exec>("buildEdlJniHost") {
    group = "build"
    description = "Builds the host libedl_jni for JVM unit tests"
    workingDir = cargoWorkspace
    commandLine("cargo", "build", "-p", "edl-jni")
    inputs.files(rustSourceFiles)
    outputs.file(hostLib)
}

tasks.withType<Test>().configureEach {
    if (!skipNative) {
        dependsOn(buildEdlJniHost)
        systemProperty("edl.jni.host.lib", hostLib.absolutePath)
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
