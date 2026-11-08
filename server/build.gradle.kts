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
    mainClass.set("com.music.server.MainKt")
    applicationName = "samm-server"
}

// The web player, built by `npm run build` in web/ into web/dist, is served from the jar.
val webDist = rootProject.layout.projectDirectory.dir("web/dist")

tasks.processResources {
    inputs.property("version", sammVersion)
    filesMatching("samm-version.properties") { expand("version" to sammVersion) }
    from(webDist) { into("web") }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
