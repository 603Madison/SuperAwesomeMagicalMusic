package com.music.server.auth

import com.music.server.ApiError
import com.music.server.db.Database
import com.music.server.db.Row
import com.music.server.util.Passwords
import com.music.server.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class Role { ADMIN, USER }

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val role: Role,
    /** The local admin made at first start, which can always sign in with its password. */
    val breakGlass: Boolean = false,
    val disabled: Boolean = false,
    /** Signs in through the identity provider (Authentik etc.). */
    val oidc: Boolean = false,
    val hasPassword: Boolean = false,
    val createdAt: Long,
    val lastLoginAt: Long? = null,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}

/** Accounts: local ones with passwords, and ones from the identity provider. */
class UserStore(
    private val db: Database,
    private val passwords: Passwords,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun count(): Long = db.tx { long("SELECT COUNT(*) FROM users") }

    suspend fun list(): List<User> = db.tx { query("SELECT * FROM users ORDER BY username COLLATE NOCASE", map = ::toUser) }

    suspend fun byId(id: String): User? = db.tx { one("SELECT * FROM users WHERE id = ?", id, map = ::toUser) }

    suspend fun byUsername(username: String): User? =
        db.tx { one("SELECT * FROM users WHERE username = ?", username.trim(), map = ::toUser) }

    suspend fun byOidc(issuer: String, subject: String): User? =
        db.tx { one("SELECT * FROM users WHERE oidc_issuer = ? AND oidc_subject = ?", issuer, subject, map = ::toUser) }

    /** The account for [username] if [password] is right (and it may sign in with one). */
    suspend fun checkPassword(username: String, password: String): User? {
        val found = db.tx {
            one("SELECT * FROM users WHERE username = ?", username.trim()) { toUser(it) to it.stringOrNull("password_hash") }
        }
        val ok = withContext(Dispatchers.Default) { passwords.verify(password, found?.second) }
        return found?.first?.takeIf { ok }
    }

    suspend fun create(
        username: String,
        displayName: String = username,
        role: Role = Role.USER,
        password: String? = null,
        breakGlass: Boolean = false,
        oidcIssuer: String? = null,
        oidcSubject: String? = null,
    ): User {
        val name = validUsername(username)
        password?.let(::validPassword)
        val hash = password?.let { withContext(Dispatchers.Default) { passwords.hash(it) } }
        val id = Random.id()
        db.tx {
            if (one("SELECT 1 FROM users WHERE username = ?", name) { true } == true) {
                throw ApiError.conflict("The username \"$name\" is taken")
            }
            update(
                """INSERT INTO users (id, username, display_name, password_hash, role, break_glass, oidc_issuer, oidc_subject, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, name, displayName.trim().ifBlank { name }.take(MAX_NAME), hash, role, breakGlass, oidcIssuer, oidcSubject, clock(),
            )
        }
        return byId(id)!!
    }

    /** A username based on [wanted] that isn't taken yet ("maddie", then "maddie-2"…). */
    suspend fun freeUsername(wanted: String): String {
        val base = wanted.trim().replace(Regex("[^A-Za-z0-9._@-]"), "-").trim('-').take(MAX_NAME - 4).ifBlank { "user" }
        return db.tx {
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
                .first { candidate -> one("SELECT 1 FROM users WHERE username = ?", candidate) { true } == null }
        }
    }

    suspend fun update(id: String, displayName: String? = null, role: Role? = null, disabled: Boolean? = null): User {
        val user = byId(id) ?: throw ApiError.notFound("No such user")
        if (user.breakGlass && (role == Role.USER || disabled == true)) {
            throw ApiError.badRequest("The break-glass admin can't be demoted or disabled")
        }
        db.tx {
            update(
                "UPDATE users SET display_name = ?, role = ?, disabled = ? WHERE id = ?",
                (displayName?.trim()?.ifBlank { null } ?: user.displayName).take(MAX_NAME),
                role ?: user.role,
                disabled ?: user.disabled,
                id,
            )
        }
        return byId(id)!!
    }

    suspend fun setPassword(id: String, password: String) {
        validPassword(password)
        val hash = withContext(Dispatchers.Default) { passwords.hash(password) }
        if (db.tx { update("UPDATE users SET password_hash = ? WHERE id = ?", hash, id) } == 0) throw ApiError.notFound("No such user")
    }

    suspend fun delete(id: String) {
        val user = byId(id) ?: throw ApiError.notFound("No such user")
        if (user.breakGlass) throw ApiError.badRequest("The break-glass admin can't be deleted")
        db.tx { update("DELETE FROM users WHERE id = ?", id) }
    }

    suspend fun touchLogin(id: String) = db.tx { update("UPDATE users SET last_login_at = ? WHERE id = ?", clock(), id) }

    suspend fun breakGlass(): User? = db.tx { one("SELECT * FROM users WHERE break_glass = 1", map = ::toUser) }

    companion object {
        const val MAX_NAME = 64
        const val MIN_PASSWORD = 8
        private val USERNAME = Regex("[A-Za-z0-9._@-]{1,$MAX_NAME}")

        fun validUsername(username: String): String {
            val name = username.trim()
            if (!USERNAME.matches(name)) {
                throw ApiError.badRequest("Usernames are 1 to $MAX_NAME letters, digits and . _ @ -")
            }
            return name
        }

        fun validPassword(password: String) {
            if (password.length < MIN_PASSWORD) throw ApiError.badRequest("Passwords need at least $MIN_PASSWORD characters")
            if (password.length > 1024) throw ApiError.badRequest("That password is too long")
        }

        fun toUser(row: Row) = User(
            id = row.string("id"),
            username = row.string("username"),
            displayName = row.string("display_name"),
            role = Role.valueOf(row.string("role")),
            breakGlass = row.bool("break_glass"),
            disabled = row.bool("disabled"),
            oidc = row.stringOrNull("oidc_subject") != null,
            hasPassword = row.stringOrNull("password_hash") != null,
            createdAt = row.long("created_at"),
            lastLoginAt = row.longOrNull("last_login_at"),
        )
    }
}
