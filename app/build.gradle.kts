import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

fun findBuildPython(): String {
    System.getenv("CHAQUOPY_BUILD_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
    val candidates = listOf(
        "/usr/bin/python3.13",
        "/usr/bin/python3.12",
        "/usr/bin/python3.11",
        "/usr/bin/python3.10",
    )
    return candidates.firstOrNull { File(it).canExecute() }
        ?: error("Set CHAQUOPY_BUILD_PYTHON to a Python 3.8-3.13 interpreter (see justfile).")
}

// The add-on is a git submodule: updating it is `git -C vendor/AI-Hints checkout <tag>`.
// Everything below is generated into build/ so no add-on source is ever committed
// into this app.
val addonRepo = rootProject.layout.projectDirectory.dir("vendor/AI-Hints")
val generatedAddon = layout.buildDirectory.dir("generated/aihints")

val syncAddonCore = tasks.register<Exec>("syncAddonCore") {
    group = "aihints"
    description = "Materialise the AI-Hints core and renderer from vendor/AI-Hints."
    workingDir = rootProject.projectDir
    val out = generatedAddon.get().asFile
    commandLine(
        findBuildPython(),
        rootProject.file("tools/materialize_core.py").absolutePath,
        "--addon", addonRepo.asFile.absolutePath,
        "--dest", File(out, "python/ai_hints_core").absolutePath,
        "--assets", File(out, "assets").absolutePath,
        "--manifest", File(out, "MANIFEST.json").absolutePath,
    )
    inputs.dir(addonRepo.dir("addon"))
    inputs.file(rootProject.file("tools/materialize_core.py"))
    inputs.file(rootProject.file("tools/gen_schema.py"))
    outputs.dir(generatedAddon)
    doFirst {
        val addon = addonRepo.dir("addon").asFile
        if (!addon.isDirectory) {
            throw GradleException(
                "vendor/AI-Hints is not checked out.\n" +
                    "Run: git submodule update --init --recursive"
            )
        }
        // addon/latex_fixer is itself a submodule; a non-recursive checkout leaves it empty.
        if (!File(addon, "latex_fixer/latex_fixer.py").isFile) {
            throw GradleException(
                "vendor/AI-Hints submodules are not checked out.\n" +
                    "Run: git submodule update --init --recursive"
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(syncAddonCore) }

// The generated tree is also a source dir for Chaquopy and the asset merger, so
// those tasks need an explicit dependency (Gradle cannot infer it from srcDir).
tasks.matching { it.name.contains("PythonSources") || it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncAddonCore) }

android {
    namespace = "dev.aihints.companion"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "dev.aihints.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    sourceSets.getByName("main") {
        assets.srcDir(generatedAddon.map { it.dir("assets") })
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        buildPython(findBuildPython())
        pyc {
            src = false
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(generatedAddon.map { it.dir("python") })
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
