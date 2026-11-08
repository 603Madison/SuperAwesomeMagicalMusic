package com.music.server

import java.io.File

/** Start-up settings, from environment variables (see server/README.md). */
data class ServerConfig(
    val dataDir: File,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Trust X-Forwarded-* headers from a reverse proxy in front of the server. */
    val trustProxy: Boolean = false,
    /** How many proxies in a row are in front (each adds the address it saw to X-Forwarded-For). */
    val proxies: Int = 1,
    /** A header the proxy in front sets to the visitor's address (Cloudflare's CF-Connecting-IP). */
    val clientIpHeader: String? = null,
    val adminUsername: String = "admin",
    /** The break-glass admin's password for a new server; generated (and logged once) if empty. */
    val adminPassword: String? = null,
    val ffmpeg: String = "ffmpeg",
    val maxTranscodes: Int = 4,
    /** Settings applied at every start, overriding the admin page (for configuration as code). */
    val overrides: Map<String, String> = emptyMap(),
) {
    val databaseFile: File get() = File(dataDir, "samm.db")
    val secretKeyFile: File get() = File(dataDir, "secret.key")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            dataDir = File(env["SAMM_DATA_DIR"]?.ifBlank { null } ?: "/data"),
            host = env["SAMM_HOST"]?.ifBlank { null } ?: "0.0.0.0",
            port = env["SAMM_PORT"]?.toIntOrNull() ?: 8080,
            trustProxy = env["SAMM_TRUST_PROXY"]?.lowercase() in setOf("1", "true", "yes"),
            proxies = env["SAMM_PROXIES"]?.trim()?.toIntOrNull()?.coerceIn(1, 10) ?: 1,
            clientIpHeader = env["SAMM_CLIENT_IP_HEADER"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) },
            adminUsername = env["SAMM_ADMIN_USERNAME"]?.ifBlank { null } ?: "admin",
            adminPassword = env["SAMM_ADMIN_PASSWORD"]?.ifBlank { null },
            ffmpeg = env["SAMM_FFMPEG"]?.ifBlank { null } ?: "ffmpeg",
            maxTranscodes = env["SAMM_MAX_TRANSCODES"]?.toIntOrNull()?.coerceIn(1, 64) ?: 4,
            overrides = env.filterKeys { it in OVERRIDES }.filterValues { it.isNotBlank() },
        )

        val OVERRIDES = setOf(
            "SAMM_PUBLIC_URL",
            "SAMM_OIDC_ISSUER",
            "SAMM_OIDC_CLIENT_ID",
            "SAMM_OIDC_CLIENT_SECRET",
            "SAMM_OIDC_BUTTON_LABEL",
            "SAMM_OIDC_USER_GROUP",
            "SAMM_OIDC_ADMIN_GROUP",
            "SAMM_OIDC_SCOPES",
            "SAMM_LOCAL_LOGIN",
        )
    }
}
