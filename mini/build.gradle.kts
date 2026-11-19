import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// One version for the whole project: the Lite app's.
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val sammVersionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
val sammVersionCode = Regex("versionCode = (\\d+)").find(appGradle)!!.groupValues[1].toInt()

android {
    namespace = "com.music.mini"
    compileSdk = 37

    defaultConfig {
        // A separate app from the others, so all three can be installed.
        applicationId = "com.music.mini"
        // Android 8: the flip phones it's made for run 8 to 13. (Older Androids also don't trust
        // Let's Encrypt's root, which most home servers use.)
        minSdk = 26
        targetSdk = 36
        versionCode = sammVersionCode
        versionName = sammVersionName
    }

    // Signed with the same permanent key as the other apps (see app/build.gradle.kts).
    val localKey = rootProject.file("keystore/release.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    fun signingValue(property: String, env: String): String? = localKey?.getProperty(property) ?: System.getenv(env)
    val keystorePath = signingValue("storeFile", "RELEASE_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS") ?: "music"
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Guava with its security fix (CVE-2026-102554), over the older one AndroidX libraries ask for.
    constraints {
        implementation(libs.guava) { because("CVE-2026-102554") }
    }
    // Plain views (no Compose) and no shared core: small and quick on phones with 512 MB.
    implementation(libs.androidx.recyclerview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    // The end-to-end test runs the real server in-process.
    testImplementation(project(":server"))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
}
