package com.music.server.db

/**
 * The database's tables, as a list of migrations. Each runs once, in order, and the database
 * remembers how many have run (SQLite's user_version); never edit one that has been released,
 * add another.
 */
object Schema {
    private val migrations: List<String> = listOf(
        // 1: accounts, sign-ins, settings, sources, playlists and listening history.
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            display_name TEXT NOT NULL,
            password_hash TEXT,
            role TEXT NOT NULL,
            break_glass INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0,
            oidc_issuer TEXT,
            oidc_subject TEXT,
            created_at INTEGER NOT NULL,
            last_login_at INTEGER,
            UNIQUE (oidc_issuer, oidc_subject)
        );
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX sessions_user ON sessions(user_id);
        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE sources (
            id TEXT PRIMARY KEY,
            owner_id TEXT REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            position INTEGER NOT NULL DEFAULT 0,
            settings TEXT NOT NULL DEFAULT '{}',
            secrets TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX sources_owner ON sources(owner_id);
        CREATE TABLE source_prefs (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
            hidden INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (user_id, source_id)
        );
        CREATE TABLE playlists (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL DEFAULT 1
        );
        CREATE INDEX playlists_user ON playlists(user_id);
        CREATE TABLE playlist_entries (
            playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            entry_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            track TEXT NOT NULL,
            added_at INTEGER NOT NULL,
            PRIMARY KEY (playlist_id, entry_id)
        );
        CREATE TABLE history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            track TEXT NOT NULL,
            played_at INTEGER NOT NULL,
            listened_ms INTEGER NOT NULL DEFAULT 0,
            client TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX history_user_time ON history(user_id, played_at DESC);
        CREATE TABLE lidarr_requests (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            artist TEXT NOT NULL COLLATE NOCASE,
            source_id TEXT NOT NULL,
            status TEXT NOT NULL,
            detail TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (user_id, artist)
        );
        """,
        // 2: what a service keeps between sign-ins (SiriusXM's session), sealed like secrets.
        """
        CREATE TABLE source_state (
            source_id TEXT PRIMARY KEY REFERENCES sources(id) ON DELETE CASCADE,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        """,
        // 3: each person's favourite live channels and stations.
        """
        CREATE TABLE live_favorites (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            track TEXT NOT NULL,
            added_at INTEGER NOT NULL,
            PRIMARY KEY (user_id, key)
        );
        """,
        // 4: when each playlist was last played, and the live channels and stations played (for
        // the sidebar's most-played ones).
        """
        ALTER TABLE playlists ADD COLUMN played_at INTEGER;
        CREATE TABLE live_plays (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            track TEXT NOT NULL,
            played_at INTEGER NOT NULL,
            plays INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (user_id, key)
        );
        """,
    )

    val version: Int get() = migrations.size

    fun migrate(db: Database) = db.blockingTx {
        val current = long("PRAGMA user_version").toInt()
        require(current <= migrations.size) {
            "This database was made by a newer SuperAwesomeMagicalMusic server (schema $current); update the server"
        }
        migrations.drop(current).forEach { migration ->
            migration.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(::execute)
        }
        execute("PRAGMA user_version = ${migrations.size}")
    }
}
