pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube Music) is only published through JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "SuperAwesomeMagicalMusic"

// core: the music services (Jellyfin, YouTube Music, Qobuz, SiriusXM…), shared by everything.
// server: the self-hosted server and its web player.
// app: the standalone Android app (SuperAwesomeMagicalMusic Lite); connect: the Android app that
// signs in to the server. Both need the Android SDK; the server's container image is built
// without them (SAMM_SERVER_ONLY=1).
include(":core", ":server")
// tuner: the FM and HD Radio tuner for a Raspberry Pi with RTL-SDR dongles (its own container
// image; the server's image is built without it).
if (file("tuner").isDirectory) include(":tuner")
if (System.getenv("SAMM_SERVER_ONLY") == null) include(":app", ":connect")
