import java.util.Properties

plugins {
    id("com.android.application")
}

// Single source of truth shared with tools/build_apk.py.
val appProps = Properties().apply { file("app.properties").inputStream().use { load(it) } }
fun prop(key: String): String = appProps.getProperty(key) ?: error("app.properties: missing $key")

// The repository root holds the files shared with the desktop Red Phone.
val repoRoot: File = rootDir.parentFile

/**
 * Copies the desktop deck UI (ui/index.html), the ChatGPT/Grok bridge scripts (extension/*.js) and
 * the handset sounds (assets/*.wav) into the APK assets — the same layout tools/build_apk.py uses.
 */
abstract class CopySharedAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedFiles: ConfigurableFileCollection

    @get:Input
    abstract val mapping: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        for ((src, dst) in mapping.get()) {
            val from = File(src)
            if (!from.isFile) continue // dialtone.wav / ringtone.wav are optional (yt-dlp downloads)
            val to = File(out, dst)
            to.parentFile.mkdirs()
            from.copyTo(to, overwrite = true)
        }
    }
}

val sharedAssetMap = linkedMapOf(
    "ui/index.html" to "ui/index.html",
    "extension/content.js" to "web/content.js",
    "extension/page-hook.js" to "web/page-hook.js",
    "assets/pickup.wav" to "sounds/pickup.wav",
    "assets/hangup.wav" to "sounds/hangup.wav",
    "assets/dialtone.wav" to "sounds/dialtone.wav",
    "assets/ringtone.wav" to "sounds/ringtone.wav",
).mapKeys { File(repoRoot, it.key).absolutePath }

val copySharedAssets = tasks.register<CopySharedAssets>("copySharedAssets") {
    sharedFiles.from(sharedAssetMap.keys.map { File(it) }.filter { it.isFile })
    mapping.set(sharedAssetMap)
}

android {
    namespace = prop("applicationId")
    compileSdk = prop("compileSdk").toInt()

    defaultConfig {
        applicationId = prop("applicationId")
        minSdk = prop("minSdk").toInt()
        targetSdk = prop("targetSdk").toInt()
        versionCode = prop("versionCode").toInt()
        versionName = prop("versionName")
    }

    compileOptions {
        // Sources are Java 8 without lambdas so the SDK-free dx build accepts them too.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-friendly default; replace with your own release signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySharedAssets, CopySharedAssets::outputDirectory)
    }
}
