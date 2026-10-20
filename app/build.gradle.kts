import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.Date
import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties()
if (versionPropertiesFile.exists()) {
    versionProperties.load(FileInputStream(versionPropertiesFile))
}

val versionMajor = versionProperties.getProperty("VERSION_MAJOR", "1").toInt()
val versionMinor = versionProperties.getProperty("VERSION_MINOR", "0").toInt()
val versionPatch = versionProperties.getProperty("VERSION_PATCH", "0").toInt()
val versionBuild = versionProperties.getProperty("VERSION_BUILD", "1").toInt()

val calculatedVersionCode = (versionMajor * 10000) + (versionMinor * 100) + versionPatch
val calculatedVersionName = "$versionMajor.$versionMinor.$versionPatch-b$versionBuild"

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use { localProperties.load(it) }
}

fun getSigningProperty(key: String): String? {
    return System.getenv(key)
        ?: (project.findProperty(key) as? String)
        ?: localProperties.getProperty(key)
}

val releaseStoreFilePath: String? = getSigningProperty("RELEASE_STORE_FILE")
val releaseStorePassword: String? = getSigningProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = getSigningProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = getSigningProperty("RELEASE_KEY_PASSWORD")

val releaseStoreFile: File? = releaseStoreFilePath?.takeIf { it.isNotBlank() }?.let { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val hasReleaseKeystore = releaseStoreFile != null &&
    releaseStoreFile.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.etaegord.ghostscreen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.etaegord.ghostscreen"
        minSdk = 26
        targetSdk = 35
        versionCode = calculatedVersionCode
        versionName = calculatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                val debugConfig = getByName("debug")
                val fallbackFile = debugConfig.storeFile ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (!fallbackFile.exists()) {
                    fallbackFile.parentFile?.mkdirs()
                    try {
                        project.exec {
                            commandLine(
                                "keytool", "-genkeypair",
                                "-alias", "androiddebugkey",
                                "-keypass", "android",
                                "-keystore", fallbackFile.absolutePath,
                                "-storepass", "android",
                                "-dname", "CN=Android Debug,O=Android,C=US",
                                "-keyalg", "RSA",
                                "-keysize", "2048",
                                "-validity", "10000"
                            )
                        }
                    } catch (e: Exception) {
                        println("Note: Fallback keystore creation: ${e.message}")
                    }
                }
                storeFile = fallbackFile
                storePassword = debugConfig.storePassword ?: "android"
                keyAlias = debugConfig.keyAlias ?: "androiddebugkey"
                keyPassword = debugConfig.keyPassword ?: "android"
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (output != null) {
                val formattedDate = SimpleDateFormat("yyyyMMdd").format(Date())
                val buildType = variant.buildType.name
                val newApkName = "GhostScreen_v${variant.versionName}_c${variant.versionCode}_${buildType}_${formattedDate}.apk"
                output.outputFileName = newApkName
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.navigation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Encrypted SharedPreferences
    implementation(libs.androidx.security.crypto)

    // WorkManager for background update checks
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
}

tasks.register("incrementVersionCode") {
    group = "versioning"
    description = "Increments the version build number (VERSION_BUILD) in version.properties"
    doLast {
        val propertiesFile = rootProject.file("version.properties")
        if (propertiesFile.exists()) {
            val properties = Properties()
            FileInputStream(propertiesFile).use { properties.load(it) }
            val currentBuild = properties.getProperty("VERSION_BUILD", "1").toInt()
            properties.setProperty("VERSION_BUILD", (currentBuild + 1).toString())
            FileOutputStream(propertiesFile).use { properties.store(it, null) }
            println("Version build incremented to ${currentBuild + 1}")
        } else {
            throw GradleException("version.properties file not found!")
        }
    }
}

tasks.register("incrementPatchVersion") {
    group = "versioning"
    description = "Increments the patch version in version.properties"
    doLast {
        val propertiesFile = rootProject.file("version.properties")
        if (propertiesFile.exists()) {
            val properties = Properties()
            FileInputStream(propertiesFile).use { properties.load(it) }
            val currentPatch = properties.getProperty("VERSION_PATCH", "0").toInt()
            properties.setProperty("VERSION_PATCH", (currentPatch + 1).toString())
            FileOutputStream(propertiesFile).use { properties.store(it, null) }
            println("Version patch incremented to ${currentPatch + 1}")
        } else {
            throw GradleException("version.properties file not found!")
        }
    }
}
