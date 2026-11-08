# SuperAwesomeMagicalMusic server

Your own music streaming service. The server signs in to your music services once (Jellyfin,
Qobuz, YouTube Music, Lidarr) and serves them to everyone you let in, with a web player for the
computer. Each person gets their own playlists and history, and can add their own services.

- **Accounts**: a local *break-glass* admin made at first start, local accounts, and single
  sign-on with any OpenID Connect provider (Authentik, Keycloak, Authelia, Zitadel…).
- **Sources**: admins share sources with everyone (the house Jellyfin, a Lidarr); people add
  their own (their own Jellyfin or Qobuz account) and can switch shared ones off for themselves.
  Saved sign-ins are encrypted.
- **Playback**: like the Lite app, songs play from your Jellyfin library when it has them, else
  the best copy on a streaming service within your quality setting. ffmpeg converts on the server
  only when needed (over your bitrate cap, or a format the browser can't play).
- **Lidarr**: artists of songs played from outside the library are added for you.

## Run it

With Docker (or Podman: the same commands with `podman`):

```sh
docker run -d --name superawesomemagicalmusic \
  -p 8080:8080 -v samm-data:/data \
  --restart unless-stopped \
  ghcr.io/603madison/superawesomemagicalmusic:latest
docker logs superawesomemagicalmusic   # shows the break-glass admin's password, once
```

Or with Compose: copy [`deploy/compose.yaml`](../deploy/compose.yaml) and `docker compose up -d`.

Open http://your-server:8080 and sign in as `admin` with the password from the log (or set
`SAMM_ADMIN_PASSWORD` before the first start). Then, under **Admin**:

1. **Shared sources**: add your Jellyfin (and Qobuz, Lidarr…). YouTube Music is shared already.
2. **People**: add accounts, or set up single sign-on (below) and let people sign in themselves.

Images are published for `linux/amd64` and `linux/arm64` (a Raspberry Pi 4 or 5 is fine). To
build one yourself: `docker build -t superawesomemagicalmusic .` in the repository.

## Single sign-on with Authentik

1. In Authentik, **Applications > Providers > Create > OAuth2/OpenID Provider**:
   - Client type: **Confidential**.
   - Redirect URI: `https://music.example.com/api/auth/oidc/callback` (the admin page shows yours).
   - Signing key: pick a certificate (RS256). Without one Authentik signs with the client secret,
     which also works.
   - The default scopes (openid, email, profile) are enough; Authentik's profile scope includes
     the person's groups.
2. **Applications > Create**, with that provider. Its slug goes in the issuer address.
3. Decide who may use it: bind groups or users to the application in Authentik, and/or name a
   group on the server's admin page ("Group allowed to sign in"). Members of the "Admin group"
   become admins; roles follow the groups at each sign-in.
4. On the server's **Admin** page, under *Single sign-on*: the issuer
   (`https://auth.example.com/application/o/<slug>/`), client ID and secret. **Check the issuer**,
   turn it on, save.

Turn off *People can sign in with a password* to make everyone use Authentik. The break-glass
admin can always sign in with its password (from the sign-in page's *Sign in with a password*),
so a broken identity provider can't lock you out.

## Settings

Environment variables (all optional):

| Variable | Default | |
| --- | --- | --- |
| `SAMM_DATA_DIR` | `/data` | Where everything is kept. |
| `SAMM_PORT` | `8080` | |
| `SAMM_PUBLIC_URL` | | The address people use, e.g. `https://music.example.com`. Needed for single sign-on behind a proxy. |
| `SAMM_TRUST_PROXY` | `false` | Believe `X-Forwarded-*` headers (only behind your own reverse proxy). |
| `SAMM_ADMIN_USERNAME` | `admin` | The break-glass admin, made at first start. |
| `SAMM_ADMIN_PASSWORD` | generated | Its first password. |
| `SAMM_MAX_TRANSCODES` | `4` | Songs converted at once. |
| `SAMM_OIDC_ISSUER`, `SAMM_OIDC_CLIENT_ID`, `SAMM_OIDC_CLIENT_SECRET`, `SAMM_OIDC_USER_GROUP`, `SAMM_OIDC_ADMIN_GROUP`, `SAMM_OIDC_BUTTON_LABEL`, `SAMM_OIDC_SCOPES`, `SAMM_LOCAL_LOGIN` | | Single sign-on and password sign-in, if you'd rather configure them here. They're applied at every start, over the admin page. |

### Behind a reverse proxy

Set `SAMM_PUBLIC_URL` and `SAMM_TRUST_PROXY=true`, and don't buffer responses (songs stream).
Caddy needs nothing special:

```
music.example.com {
    reverse_proxy superawesomemagicalmusic:8080
}
```

nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
}
```

## Look after it

- **Back up** the data folder: `samm.db` (accounts, playlists, sources) and `secret.key`. Without
  the key, saved sign-ins (Jellyfin tokens, Qobuz and API keys) can't be read and must be entered
  again; keep it as private as a password.
- **Update**: `docker pull ghcr.io/603madison/superawesomemagicalmusic:latest` and recreate the
  container. The database updates itself.
- **Lost the admin password?**
  `docker exec superawesomemagicalmusic /opt/samm/bin/samm-server reset-admin-password` prints a
  new one (or give it one: `… reset-admin-password 'new password'`).

## Security notes

- Passwords are stored as PBKDF2 hashes; sign-ins are random tokens stored hashed. Browsers get an
  HttpOnly, SameSite=Strict cookie, and every change needs a header other sites can't send.
  Repeated wrong passwords are slowed down.
- Single sign-on uses the authorization code flow with PKCE; ID tokens are checked (signature,
  issuer, audience, expiry, nonce). Accounts are tied to the provider's subject, so a local
  account is never taken over by someone signing in with the same name.
- People who add their own sources make the server connect to addresses they choose. Turn off
  *People can add their own sources* if that's not wanted on your network.
- The server talks to the services, so your Jellyfin and Lidarr needn't be reachable from outside.

## Develop

```sh
./gradlew :server:test                     # tests (one needs ffmpeg installed)
cd web && npm install && npm run build     # the web player, bundled into the server
./gradlew :server:installDist
SAMM_DATA_DIR=./data server/build/install/samm-server/bin/samm-server
cd web && npm run dev                      # live-reloading web player on :5173, API from :8080
```
