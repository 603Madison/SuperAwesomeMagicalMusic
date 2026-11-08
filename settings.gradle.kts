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

// app: the standalone Android app (SuperAwesomeMagicalMusic Lite).
// core: the music services (Jellyfin, YouTube Music, Qobuz, SiriusXM…), shared with the server.
include(":app", ":core")
