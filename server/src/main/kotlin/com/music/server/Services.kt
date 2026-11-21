package com.music.server

import com.music.player.AppInfo
import com.music.player.CoreLog
import com.music.player.net.Http
import com.music.player.net.call
import com.music.server.auth.LoginThrottle
import com.music.server.auth.OidcClient
import com.music.server.auth.OrchestratorTickets
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
    http: OkHttpClient = Http.createClient(),
    passwords: Passwords = Passwords(),
    val clock: () -> Long = System::currentTimeMillis,
    /** The server's own address, for ffmpeg to read from. */
    selfUrl: () -> String = { "http://127.0.0.1:${config.port}" },
    /** How SuperAwesomeMagicalOrchestrator's published keys are fetched (a stand-in in tests); null: with [orchestratorHttp]. */
    orchestratorFetch: (suspend (url: String) -> String)? = null,
) : AutoCloseable {
    /** Every request the server makes, kept off places it mustn't reach (see [NetworkGuard]). */
    val http: OkHttpClient = http.newBuilder().addNetworkInterceptor(com.music.server.util.NetworkGuard.interceptor()).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** When the server started, for the connector's status. */
    val startedAt = clock()
    val users = UserStore(db, passwords, clock)
    val sessions = SessionStore(db, clock)
    val settings = SettingsStore(db, box)
    val oidc = OidcClient(http, clock)
    val pending = PendingLogins(clock)
    val throttle = LoginThrottle(clock = clock)

    /**
     * Only for SuperAwesomeMagicalOrchestrator's published keys. Its address is the admin's own
     * choice (the Admin page or SAMM_ORCHESTRATOR_URL, never anything a stranger sends), and it
     * usually lives on the home network, which [NetworkGuard] lets the server's requests reach
     * (never link-local or a cloud's metadata service). No redirects, so the answer comes from
     * the address the admin set.
     */
    private val orchestratorHttp: OkHttpClient = this.http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Checks SuperAwesomeMagicalOrchestrator's sign-in tickets (its keys: a small answer, 256 KB at most). */
    val orchestrator = OrchestratorTickets(
        fetch = orchestratorFetch ?: { url -> orchestratorKeys(url) },
        clock = clock,
    )

    private suspend fun orchestratorKeys(url: String): String = orchestratorHttp.call(okhttp3.Request.Builder().url(url).build()) { response ->
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        val source = response.body.source()
        if (source.request(ORCHESTRATOR_KEYS_MAX + 1)) throw java.io.IOException("The answer is too long")
        source.readUtf8()
    }
    /** Signing in with a code shown on another device (see DeviceRoutes). */
    val deviceLogins = com.music.server.auth.DeviceLogins(clock)
    /** Wrong codes typed to approve a device: 10 for one person from one place, fewer than passwords. */
    val codeThrottle = LoginThrottle(
        maxFailures = 10, maxFailuresPerAddress = 30, maxFailuresPerAccount = 20, clock = clock,
        message = "Too many wrong codes. Try again in a few minutes.",
    )
    val sources = SourceStore(db, box, clock)
    val setup = SourceSetup(http)
    val registry = SourceRegistry(http, sources) { owner -> config.localSources || kotlinx.coroutines.runBlocking { users.byId(owner) }?.isAdmin == true }
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
    /** Local FM and HD Radio from tuners on the network, alongside internet radio. */
    val tunerRadio = com.music.server.tuner.TunerRadio(this)
    val missingFinder = com.music.server.playlists.MissingFinder(playlists, users, importer, scope)
    val personalRadio = com.music.server.radio.PersonalRadio(this)
    /** Listen: recognising songs, from the libraries it learns (kept in listen.db) and AudD. */
    val listen = com.music.server.listen.Listen(this, java.io.File(config.dataDir, "listen.db"))

    override fun close() {
        tunerRadio.close()
        scope.cancel()
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("samm")

        private const val ORCHESTRATOR_KEYS_MAX = 256L * 1024

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
                // Straight to the console, not the log: the log is kept for the admin page and
                // the MCP connector, and a password doesn't belong in either.
                System.err.println(
                    "\n\n  Created the break-glass admin account.\n  Username: ${config.adminUsername}\n  Password: $password\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samm-server reset-admin-password`.\n",
                )
                System.err.flush()
                log.warn("Created the break-glass admin account {}; its password is in the console output above", config.adminUsername)
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
                    orchestratorUrl = env["SAMM_ORCHESTRATOR_URL"]?.trim()?.trimEnd('/') ?: current.orchestratorUrl,
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
