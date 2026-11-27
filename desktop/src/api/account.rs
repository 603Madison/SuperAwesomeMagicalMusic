//! The account side of the server's API (as web/src/lib/api.ts has it): you, where you're signed
//! in, signing in a device with a code, your sources, and (for admins) people and the server.

use anyhow::Result;
use reqwest::Method;
use serde::Deserialize;
use serde_json::{Value, json};

use super::{Api, SourceView, User, enc};

/// A sign-in: a browser, an app, or a Claude connector link.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionInfo {
    pub id: String,
    /// WEB, APP, MCP or MCP_ALL.
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub last_seen_at: i64,
    #[serde(default)]
    pub current: bool,
}

impl SessionInfo {
    pub fn is_connector(&self) -> bool {
        self.kind.starts_with("MCP")
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Me {
    pub user: User,
}

/// A device waiting to be signed in with a code.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceRequest {
    pub user_code: String,
    #[serde(default)]
    pub device_name: String,
}

/// What a test, or a check, found.
#[derive(Debug, Clone, Deserialize)]
pub struct Checked {
    #[serde(default)]
    pub ok: bool,
    #[serde(default)]
    pub message: String,
}

/// An artist added to Lidarr (or not) because their songs were played.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LidarrRequest {
    pub artist: String,
    /// added, present, not_found or failed.
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub detail: String,
    #[serde(default)]
    pub updated_at: i64,
}

// ---- Sources ----

/// What a kind of source asks for.
#[derive(Debug, Clone, Deserialize)]
pub struct FieldSpec {
    pub key: String,
    pub label: String,
    /// text, url, password, bool or select.
    #[serde(default = "text", rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub required: bool,
    /// Never sent back; left empty when editing, it keeps what's saved.
    #[serde(default)]
    pub secret: bool,
    #[serde(default)]
    pub help: Option<String>,
    #[serde(default)]
    pub options: Option<Vec<String>>,
    #[serde(default)]
    pub default: Option<String>,
}

fn text() -> String {
    "text".into()
}

#[derive(Debug, Clone, Deserialize)]
pub struct KindSpec {
    pub kind: String,
    pub label: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub fields: Vec<FieldSpec>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Kinds {
    #[serde(default)]
    pub kinds: Vec<KindSpec>,
    #[serde(default)]
    pub can_add_personal: bool,
    #[serde(default)]
    pub can_add_global: bool,
}

impl Kinds {
    pub fn spec(&self, kind: &str) -> Option<&KindSpec> {
        self.kinds.iter().find(|k| k.kind == kind)
    }
}

/// A choice Lidarr offers (a profile, a root folder).
#[derive(Debug, Clone, Deserialize)]
pub struct Choice {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LidarrOptions {
    #[serde(default)]
    pub quality_profiles: Vec<Choice>,
    #[serde(default)]
    pub metadata_profiles: Vec<Choice>,
    #[serde(default)]
    pub root_folders: Vec<Choice>,
}

/// The server's settings, as an admin sees them. [settings] is kept as the server sent it, so
/// what this app doesn't know about is saved back unchanged.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminSettings {
    pub settings: Value,
    #[serde(default)]
    pub oidc_client_secret_set: bool,
    #[serde(default)]
    pub redirect_uri: String,
    #[serde(default)]
    pub from_environment: Vec<String>,
    #[serde(default)]
    pub version: String,
}

impl Api {
    // You.

    pub async fn me(&self) -> Result<Me> {
        self.get("/api/me").await
    }

    pub async fn update_profile(&self, display_name: &str) -> Result<User> {
        self.send(Method::PATCH, "/api/me", Some(json!({ "displayName": display_name })))
            .await
    }

    /// Changes your password (and signs you out everywhere else).
    pub async fn change_password(&self, current: &str, new: &str) -> Result<Value> {
        self.post("/api/me/password", json!({ "current": current, "new": new })).await
    }

    pub async fn sessions(&self) -> Result<Vec<SessionInfo>> {
        self.get("/api/me/sessions").await
    }

    pub async fn revoke_session(&self, id: &str) -> Result<Value> {
        self.send(Method::DELETE, &format!("/api/me/sessions/{}", enc(id)), None).await
    }

    /// A link for Claude's connector (shown once): it can look, never change anything.
    pub async fn mcp_link(&self, all_accounts: bool) -> Result<String> {
        #[derive(Deserialize)]
        struct Link {
            url: String,
        }
        let link: Link = self.post("/api/me/mcp-links", json!({ "allAccounts": all_accounts })).await?;
        Ok(link.url)
    }

    pub async fn lidarr_requests(&self) -> Result<Vec<LidarrRequest>> {
        self.get("/api/lidarr/requests").await
    }

    // Signing in another device with the code it shows.

    pub async fn device_request(&self, code: &str) -> Result<DeviceRequest> {
        self.get(&format!("/api/auth/device/request?code={}", enc(code))).await
    }

    pub async fn device_decide(&self, user_code: &str, allow: bool) -> Result<Value> {
        self.post("/api/auth/device/decide", json!({ "userCode": user_code, "allow": allow }))
            .await
    }

    // Sources.

    pub async fn source_kinds(&self) -> Result<Kinds> {
        self.get("/api/sources/kinds").await
    }

    /// Adds a source: yours ("personal"), or everyone's ("global", for admins).
    pub async fn add_source(&self, kind: &str, name: &str, scope: &str, fields: Value) -> Result<SourceView> {
        self.post(
            "/api/sources",
            json!({ "kind": kind, "name": name, "scope": scope, "fields": fields }),
        )
        .await
    }

    pub async fn update_source(&self, id: &str, change: Value) -> Result<SourceView> {
        self.send(Method::PATCH, &format!("/api/sources/{}", enc(id)), Some(change)).await
    }

    pub async fn delete_source(&self, id: &str) -> Result<Value> {
        self.send(Method::DELETE, &format!("/api/sources/{}", enc(id)), None).await
    }

    /// Switches a shared source off (or on again) for yourself.
    pub async fn hide_source(&self, id: &str, hidden: bool) -> Result<SourceView> {
        self.send(
            Method::PUT,
            &format!("/api/sources/{}/hidden", enc(id)),
            Some(json!({ "hidden": hidden })),
        )
        .await
    }

    pub async fn test_source(&self, id: &str) -> Result<Checked> {
        self.post(&format!("/api/sources/{}/test", enc(id)), json!({})).await
    }

    pub async fn lidarr_options(&self, id: &str) -> Result<LidarrOptions> {
        self.get(&format!("/api/sources/{}/lidarr-options", enc(id))).await
    }

    // Admin: people and the server.

    pub async fn users(&self) -> Result<Vec<User>> {
        self.get("/api/admin/users").await
    }

    pub async fn create_user(&self, username: &str, display_name: &str, password: &str, role: &str) -> Result<User> {
        self.post(
            "/api/admin/users",
            json!({ "username": username, "displayName": display_name, "password": password, "role": role }),
        )
        .await
    }

    /// Changes someone's role ("ADMIN", "USER") or turns their account off or on.
    pub async fn update_user(&self, id: &str, change: Value) -> Result<User> {
        self.send(Method::PATCH, &format!("/api/admin/users/{}", enc(id)), Some(change))
            .await
    }

    pub async fn delete_user(&self, id: &str) -> Result<Value> {
        self.send(Method::DELETE, &format!("/api/admin/users/{}", enc(id)), None).await
    }

    pub async fn reset_password(&self, id: &str, password: &str) -> Result<Value> {
        self.post(&format!("/api/admin/users/{}/password", enc(id)), json!({ "password": password }))
            .await
    }

    pub async fn sign_out_user(&self, id: &str) -> Result<Value> {
        self.send(Method::DELETE, &format!("/api/admin/users/{}/sessions", enc(id)), None)
            .await
    }

    pub async fn admin_settings(&self) -> Result<AdminSettings> {
        self.get("/api/admin/settings").await
    }

    /// Saves the server's settings; [secret] replaces the single sign-on client secret when given.
    pub async fn save_settings(&self, settings: &Value, secret: Option<&str>) -> Result<AdminSettings> {
        let mut body = json!({ "settings": settings });
        if let Some(secret) = secret {
            body["oidcClientSecret"] = json!(secret);
        }
        self.send(Method::PUT, "/api/admin/settings", Some(body)).await
    }

    pub async fn test_oidc(&self, oidc: &Value) -> Result<Checked> {
        self.post("/api/admin/oidc/test", oidc.clone()).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_and_sources_read_what_the_server_sends() {
        let kinds: Kinds = serde_json::from_value(json!({
            "kinds": [{ "kind": "JELLYFIN", "label": "Jellyfin", "description": "Your library.",
                "fields": [{ "key": "serverUrl", "label": "Server address", "type": "url", "required": true },
                           { "key": "password", "label": "Password", "type": "password", "secret": true }] }],
            "canAddPersonal": true, "canAddGlobal": false
        }))
        .unwrap();
        let spec = kinds.spec("JELLYFIN").unwrap();
        assert_eq!(spec.fields[0].kind, "url");
        assert!(spec.fields[1].secret);
        assert!(kinds.can_add_personal && !kinds.can_add_global);
        let source: SourceView = serde_json::from_value(json!({
            "id": "s", "kind": "JELLYFIN", "name": "Home", "scope": "global", "enabled": true, "hidden": false,
            "editable": false, "settings": { "serverUrl": "https://jf" }, "secretsSet": ["accessToken"]
        }))
        .unwrap();
        assert_eq!(source.settings["serverUrl"], "https://jf");
        assert_eq!(source.secrets_set, vec!["accessToken"]);
    }
}
