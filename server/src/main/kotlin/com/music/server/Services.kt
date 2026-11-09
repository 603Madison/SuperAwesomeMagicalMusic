package com.music.server

import com.music.player.AppInfo
import com.music.player.CoreLog
import com.music.player.net.Http
import com.music.server.auth.LoginThrottle
import com.music.server.auth.OidcClient
import com.music.server.auth.PendingLogins
import com.music.server.auth.Role
import com.music.server.auth.SessionStore
import com.music.server.auth.UserStore
import com.music.server.db.Database
import com.music.server.db.Schema
import com.music.server.music.Artwork
import com.music.server.music.LiveFavorites
import com.music.server.music.SongArt
import com.music.server.music.BrowseViews
import com.music.server.music.Browser
import com.music.server.playlists.Importer
import com.music.server.music.Catalog
import com.music.server.music.LidarrRequests
import com.music.server.music.LiveProxy
import com.music.server.music.Streamer
import com.music.server.playlists.HistoryStore
import com.music.server.playlists.PlaylistStore
import com.music.server.sources.SourceKind
import com.music.server.sources.SourceRegistry
import com.music.server.sources.SourceSetup
import com.music.server.sources.SourceStore
import com.music.server.util.Passwords
import com.music.server.util.Random
import com.music.server.util.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.Properties

/** Everything the server is made of, built once at start-up. */
class Services(
    val config: ServerConfig,
    val db: Database,
    val box: SecretBox,
    val http: OkHttpClient = Http.createClient(),
    passwords: Passwords = Passwords(),
    val clock: () -> Long = System::currentTimeMillis,
    /** The server's own address, for ffmpeg to read from. */
    selfUrl: () -> String = { "http://127.0.0.1:${config.port}" },
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val users = UserStore(db, passwords, clock)
    val sessions = SessionStore(db, clock)
    val settings = SettingsStore(db, box)
    val oidc = OidcClient(http, clock)
    val pending = PendingLogins(clock)
    val throttle = LoginThrottle(clock = clock)
    val sources = SourceStore(db, box, clock)
    val setup = SourceSetup(http)
    val registry = SourceRegistry(http, sources)
    val lidarr = LidarrRequests(db, sources, registry, http, scope, clock)
    val catalog = Catalog(sources, registry, lidarr, clock)
    val streamer = Streamer(http, config.ffmpeg, config.maxTranscodes, selfUrl)
    val playlists = PlaylistStore(db, clock)
    val history = HistoryStore(db)
    val artwork = Artwork(box)
    val browser = Browser(sources, registry)
    val importer = Importer(http, sources, registry)
    val views = BrowseViews(artwork)
    val live = LiveProxy(http, box, clock)
    val liveFavorites = LiveFavorites(db, clock)
    val connect = com.music.server.connect.ConnectHub(clock)
    val songArt = SongArt(sources, registry, scope, clock)
    val missingFinder = com.music.server.playlists.MissingFinder(playlists, users, importer, scope)

    override fun close() {
        scope.cancel()
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("samm")

        val version: String by lazy {
            runCatching {
                Properties().apply { Services::class.java.getResourceAsStream("/samm-version.properties")?.use(::load) }.getProperty("version")
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\$") } ?: "dev"
        }

        /** Opens the data folder and database, and routes the shared code's logging to the server's. */
        fun open(config: ServerConfig): Services {
            config.dataDir.mkdirs()
            AppInfo.name = "SuperAwesomeMagicalMusic-Server"
            AppInfo.version = version
            AppInfo.platform = "Server"
            AppInfo.deviceName = "SuperAwesomeMagicalMusic server"
            CoreLog.sink = { level, tag, message, error ->
                val logger = LoggerFactory.getLogger(tag)
                when (level) {
                    CoreLog.Level.DEBUG -> logger.debug(message, error)
                    CoreLog.Level.INFO -> logger.info(message, error)
                    CoreLog.Level.WARN -> logger.warn(message, error)
                    CoreLog.Level.ERROR -> logger.error(message, error)
                }
            }
            val db = Database.file(config.databaseFile.path)
            Schema.migrate(db)
            return Services(config, db, SecretBox.fromFile(config.secretKeyFile))
        }
    }

    /**
     * First start: makes the break-glass admin and shares YouTube Music with everyone. Every
     * start: applies settings given as environment variables.
     */
    suspend fun bootstrap() {
        if (users.count() == 0L) {
            val password = config.adminPassword ?: Random.password()
            users.create(config.adminUsername, "Administrator", Role.ADMIN, password, breakGlass = true)
            if (config.adminPassword == null) {
                log.warn(
                    "\n\n  Created the break-glass admin account.\n  Username: {}\n  Password: {}\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samm-server reset-admin-password`.\n",
                    config.adminUsername, password,
                )
            } else {
                log.info("Created the break-glass admin account {} with the password from SAMM_ADMIN_PASSWORD", config.adminUsername)
            }
        }
        seed(SourceKind.YOUTUBE_MUSIC, "YouTube Music")
        seed(SourceKind.RADIO, "Radio")
        val env = config.overrides
        if (env.isNotEmpty()) {
            settings.update { current ->
                current.copy(
                    publicUrl = env["SAMM_PUBLIC_URL"]?.trimEnd('/') ?: current.publicUrl,
                    localLogin = env["SAMM_LOCAL_LOGIN"]?.let { it.lowercase() in setOf("1", "true", "yes") } ?: current.localLogin,
                    oidc = current.oidc.copy(
                        enabled = if (env["SAMM_OIDC_ISSUER"] != null && env["SAMM_OIDC_CLIENT_ID"] != null) true else current.oidc.enabled,
                        issuer = env["SAMM_OIDC_ISSUER"] ?: current.oidc.issuer,
                        clientId = env["SAMM_OIDC_CLIENT_ID"] ?: current.oidc.clientId,
                        buttonLabel = env["SAMM_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAMM_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAMM_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAMM_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAMM_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it) }
        }
        sessions.purgeExpired()
    }

    /** Shares a source that needs no sign-in with everyone, once (an admin may remove it after). */
    private suspend fun seed(kind: SourceKind, name: String) {
        val key = "seeded.${kind.name}"
        val seeded = db.tx { one("SELECT 1 FROM settings WHERE key = ?", key) { true } } == true
        if (seeded) return
        if (sources.allGlobal().none { it.kind == kind }) {
            sources.create(ownerId = null, kind = kind, name = name, settings = JsonObject(emptyMap()), secrets = JsonObject(emptyMap()))
        }
        db.tx { update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')", key) }
    }

    /** Sets a new password for the break-glass admin (from the command line) and returns it. */
    suspend fun resetBreakGlass(password: String?): Pair<String, String> {
        val admin = users.breakGlass() ?: users.create(config.adminUsername, "Administrator", Role.ADMIN, Random.password(), breakGlass = true)
        val newPassword = password ?: Random.password()
        users.setPassword(admin.id, newPassword)
        users.update(admin.id, role = Role.ADMIN, disabled = false)
        sessions.revokeAll(admin.id)
        return admin.username to newPassword
    }
}
