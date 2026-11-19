// The FM and HD Radio tuner: a small server for a Raspberry Pi with RTL-SDR dongles, which the
// music server (or anyone on the network) listens through. Plain Kotlin/JVM with Ktor, like the
// server, and no other part of the project, so its container image stays small.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// One version for the whole project: the Android app's versionName.
val sammVersion: String = Regex("versionName = \"([^\"]+)\"")
    .find(rootProject.file("app/build.gradle.kts").readText())?.groupValues?.get(1) ?: "dev"

version = sammVersion

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.music.tuner.MainKt")
    applicationName = "samm-tuner"
}

tasks.processResources {
    inputs.property("version", sammVersion)
    filesMatching("tuner-version.properties") { expand("version" to sammVersion) }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // Netty with its security fixes, over the older one Ktor asks for.
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}
