package com.music.server.sources

import com.music.player.model.SourceType
import com.music.server.ApiError
import com.music.server.auth.User
import com.music.server.db.Database
import com.music.server.db.Row
import com.music.server.db.Tx
import com.music.server.util.Random
import com.music.server.util.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The kinds of source the server can use. */
@Serializable
enum class SourceKind(val label: String, val type: SourceType?) {
    JELLYFIN("Jellyfin", SourceType.JELLYFIN),
    QOBUZ("Qobuz", SourceType.QOBUZ),
    YOUTUBE_MUSIC("YouTube Music", SourceType.YOUTUBE_MUSIC),
    LIDARR("Lidarr", null),
    SIRIUSXM("SiriusXM", SourceType.SIRIUSXM),
    RADIO("Radio", SourceType.RADIO),
    ;

    /** Live radio: channels and stations rather than songs on demand. */
    val isLive: Boolean get() = this == SIRIUSXM || this == RADIO

    /** A library: searched first, and what Lidarr fills. */
    val isLibrary: Boolean get() = this == JELLYFIN

    /** Plays songs on demand (as opposed to radio, or helpers like Lidarr). */
    val isOnDemand: Boolean get() = type?.onDemand == true
}

/** A source as saved: [secrets] are decrypted only in memory. */
data class StoredSource(
    val id: String,
    /** The user it belongs to, or null for one the admins share with everyone. */
    val ownerId: String?,
    val kind: SourceKind,
    val name: String,
    val enabled: Boolean,
    val position: Int,
    val settings: JsonObject,
    val secrets: JsonObject,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isGlobal: Boolean get() = ownerId == null

    fun setting(key: String): String? = (settings[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    fun settingInt(key: String): Int? = (settings[key] as? JsonPrimitive)?.intOrNull
    fun settingBool(key: String, default: Boolean): Boolean = (settings[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun secret(key: String): String = (secrets[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun canEdit(user: User): Boolean = if (isGlobal) user.isAdmin else ownerId == user.id
}

/** A source as shown to someone: never with its secrets. */
@Serializable
data class SourceView(
    val id: String,
    val kind: SourceKind,
    val name: String,
    /** "global" (shared by the admins) or "personal". */
    val scope: String,
    val enabled: Boolean,
    /** Hidden by this person (a shared source they don't want to use). */
    val hidden: Boolean,
    val editable: Boolean,
    val settings: JsonObject,
    /** The secrets that are set, by name (their values are never sent). */
    val secretsSet: List<String>,
)

/** Saved sources: the ones admins share with everyone, and each person's own. */
class SourceStore(
    private val db: Database,
    private val box: SecretBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Everything [user] can see: shared sources (marked if they hid them) and their own. */
    suspend fun visibleTo(user: User): List<SourceView> = db.tx {
        val hidden = query("SELECT source_id FROM source_prefs WHERE user_id = ? AND hidden = 1", user.id) { it.string("source_id") }.toSet()
        rows("WHERE owner_id IS NULL OR owner_id = ?", user.id).map { it.view(user, it.id in hidden) }
    }

    /** The sources [user]'s searches and playback use: theirs first, then the shared ones. */
    suspend fun activeFor(user: User): List<StoredSource> = db.tx {
        val hidden = query("SELECT source_id FROM source_prefs WHERE user_id = ? AND hidden = 1", user.id) { it.string("source_id") }.toSet()
        rows("WHERE enabled = 1 AND (owner_id IS NULL OR owner_id = ?)", user.id).filter { it.id !in hidden }
    }

    suspend fun get(id: String): StoredSource? = db.tx { rows("WHERE id = ?", id).firstOrNull() }

    /** [id] if [user] may use it. */
    suspend fun accessible(user: User, id: String): StoredSource? =
        get(id)?.takeIf { it.isGlobal || it.ownerId == user.id }

    suspend fun allGlobal(): List<StoredSource> = db.tx { rows("WHERE owner_id IS NULL") }

    suspend fun create(
        id: String = Random.id(),
        ownerId: String?,
        kind: SourceKind,
        name: String,
        settings: JsonObject,
        secrets: JsonObject,
    ): StoredSource {
        val now = clock()
        db.tx {
            val position = long("SELECT COALESCE(MAX(position), -1) + 1 FROM sources WHERE owner_id IS ?", ownerId).toInt()
            update(
                """INSERT INTO sources (id, owner_id, kind, name, enabled, position, settings, secrets, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)""",
                id, ownerId, kind, cleanName(name, kind), position, settings.toString(), seal(id, secrets), now, now,
            )
        }
        return get(id)!!
    }

    suspend fun update(
        id: String,
        name: String? = null,
        enabled: Boolean? = null,
        settings: JsonObject? = null,
        secrets: JsonObject? = null,
        position: Int? = null,
    ): StoredSource {
        val current = get(id) ?: throw ApiError.notFound("No such source")
        db.tx {
            update(
                "UPDATE sources SET name = ?, enabled = ?, settings = ?, secrets = ?, position = ?, updated_at = ? WHERE id = ?",
                name?.let { cleanName(it, current.kind) } ?: current.name,
                enabled ?: current.enabled,
                (settings ?: current.settings).toString(),
                seal(id, secrets ?: current.secrets),
                position ?: current.position,
                // Strictly increasing, so a change always replaces the source's cached client.
                maxOf(clock(), current.updatedAt + 1),
                id,
            )
        }
        return get(id)!!
    }

    suspend fun delete(id: String) = db.tx { update("DELETE FROM sources WHERE id = ?", id) }

    /** What a service keeps between sign-ins (sealed like the source's secrets). */
    suspend fun loadState(sourceId: String): String? = db.tx {
        one("SELECT value FROM source_state WHERE source_id = ?", sourceId) { it.string("value") }
    }?.let { runCatching { box.open(it, "state:$sourceId") }.getOrNull() }

    suspend fun saveState(sourceId: String, value: String?) = db.tx {
        if (value == null) {
            update("DELETE FROM source_state WHERE source_id = ?", sourceId)
        } else {
            update(
                "INSERT INTO source_state (source_id, value, updated_at) VALUES (?, ?, ?) ON CONFLICT (source_id) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                sourceId, box.seal(value, "state:$sourceId"), clock(),
            )
        }
    }

    suspend fun setHidden(userId: String, sourceId: String, hidden: Boolean) = db.tx {
        update(
            "INSERT INTO source_prefs (user_id, source_id, hidden) VALUES (?, ?, ?) ON CONFLICT (user_id, source_id) DO UPDATE SET hidden = excluded.hidden",
            userId, sourceId, hidden,
        )
    }

    private fun Tx.rows(where: String, vararg args: Any?): List<StoredSource> =
        query("SELECT * FROM sources $where ORDER BY owner_id IS NULL, position, created_at", *args) { row -> toSource(row) }

    private fun toSource(row: Row): StoredSource {
        val id = row.string("id")
        return StoredSource(
            id = id,
            ownerId = row.stringOrNull("owner_id"),
            kind = SourceKind.entries.firstOrNull { it.name == row.string("kind") } ?: SourceKind.YOUTUBE_MUSIC,
            name = row.string("name"),
            enabled = row.bool("enabled"),
            position = row.int("position"),
            settings = parse(row.string("settings")),
            secrets = row.stringOrNull("secrets")?.let { sealed ->
                runCatching { parse(box.open(sealed, "source:$id")) }.getOrElse { JsonObject(emptyMap()) }
            } ?: JsonObject(emptyMap()),
            createdAt = row.long("created_at"),
            updatedAt = row.long("updated_at"),
        )
    }

    private fun seal(id: String, secrets: JsonObject): String? =
        if (secrets.isEmpty()) null else box.seal(secrets.toString(), "source:$id")

    private fun StoredSource.view(user: User, hidden: Boolean) = SourceView(
        id = id,
        kind = kind,
        name = name,
        scope = if (isGlobal) "global" else "personal",
        enabled = enabled,
        hidden = hidden,
        editable = canEdit(user),
        settings = settings,
        secretsSet = secrets.filterValues { (it as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }.keys.sorted(),
    )

    private fun cleanName(name: String, kind: SourceKind) = name.trim().take(80).ifBlank { kind.label }

    private fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
}
