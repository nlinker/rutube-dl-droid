plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.rust.android)
}

android {
    namespace = "io.github.nlinker.rutubedl.bindings"
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

cargo {
    module = "../../rust"
    libname = "rutube_ffi"
    targets = listOf("arm64")
    // The API level the NDK links against; keep it equal to minSdk above.
    apiLevel = 29
    // Release even in debug builds: the debug library is 145 MB of symbols we
    // cannot read on the device anyway, and Rust is debugged on the desktop.
    profile = "release"
    extraCargoBuildArguments = listOf("--package", "rutube-ffi")
}

// UniFFI reads the metadata the macros left in the compiled library, so the
// bindings are generated from the .so the plugin has just built. A typed task
// rather than a plain Exec: `addGeneratedSourceDirectory` wires the output
// directory itself, and AGP 9 has no other way to add generated Kotlin.
abstract class GenerateBindings : DefaultTask() {
    @get:InputFile
    abstract val library: RegularFileProperty

    // `uniffi.toml` next to the crate: package name and Kotlin options.
    @get:InputFile
    abstract val config: RegularFileProperty

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(
                "cargo", "run", "--quiet", "--package", "uniffi-bindgen", "--",
                "generate", "--library", library.get().asFile.absolutePath,
                "--language", "kotlin", "--no-format",
                "--out-dir", outputDir.get().asFile.absolutePath,
            )
        }
    }
}

val generateBindings = tasks.register<GenerateBindings>("generateBindings") {
    dependsOn("cargoBuild")
    library = layout.buildDirectory.file("rustJniLibs/android/arm64-v8a/librutube_ffi.so")
    config = layout.file(provider { rootDir.parentFile.resolve("rust/rutube-ffi/uniffi.toml") })
    workspace = layout.dir(provider { rootDir.parentFile.resolve("rust") })
    outputDir = layout.buildDirectory.dir("generated/source/uniffi/kotlin")
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateBindings, GenerateBindings::outputDir)
}

dependencies {
    // UniFFI calls into the library through JNA; the aar carries its own .so files.
    //noinspection UseTomlInstead
    api("net.java.dev.jna:jna:5.18.1@aar")
    // The generated bindings turn every async fn into a suspend fun, so they
    // import kotlinx.coroutines. `api`: the types appear in their signatures.
    api(libs.kotlinx.coroutines)
    // `android = true` in uniffi.toml makes the bindings use @RequiresApi.
    implementation(libs.androidx.annotation)
}
