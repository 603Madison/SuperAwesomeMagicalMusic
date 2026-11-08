# SuperAwesomeMagicalMusic server

Your own music streaming service. The server signs in to your music services once (Jellyfin,
YouTube Music, Lidarr) and serves them to everyone you let in, with a web player for the
computer. Each person gets their own playlists and history, and can add their own services.

- **Accounts**: a local *break-glass* admin made at first start, local accounts, and single
  sign-on with any OpenID Connect provider (Authentik, Keycloak, Authelia, Zitadel…).
- **Sources**: admins share sources with everyone (the house Jellyfin, a Lidarr); people add
  their own (their own Jellyfin, for one) and can switch shared ones off for themselves.
  Saved sign-ins are encrypted.
- **Playback**: like the Lite app, songs play from your Jellyfin library when it has them, else
  the best copy on a streaming service within your quality setting. ffmpeg converts on the server
  only when needed (over your bitrate cap, or a format the browser can't play).
- **Live radio**: SiriusXM channels (with what's on now, and Xtra channels) and Pandora artist
  stations through SiriusXM, each person with their own SiriusXM account; and tens of thousands of
  local and internet stations from Radio Browser, by name or near you, with song titles shown.
  Everything streams through the server, so it works from anywhere the server is reachable.
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

### With Podman, started by systemd

Podman's Quadlet (Podman 4.4 and later) turns a small file into a systemd service. As the user
that runs it, save this as `~/.config/containers/systemd/superawesomemagicalmusic.container`
(or, as root, in `/etc/containers/systemd/`):

```ini
[Unit]
Description=SuperAwesomeMagicalMusic

[Container]
Image=ghcr.io/603madison/superawesomemagicalmusic:latest
ContainerName=superawesomemagicalmusic
# Only the reverse proxy on this machine reaches it.
PublishPort=127.0.0.1:8080:8080
# A named volume: Podman gives it to the container's user (a folder you made yourself would
# need UserNS=keep-id:uid=1000,gid=1000 with rootless Podman).
Volume=samm-data:/data
Environment=SAMM_PUBLIC_URL=https://music.example.com
Environment=SAMM_TRUST_PROXY=true
Environment=TZ=America/New_York
# With podman-auto-update.timer on, new releases are pulled and started by themselves.
AutoUpdate=registry

[Service]
Restart=always

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload
systemctl --user start superawesomemagicalmusic
loginctl enable-linger "$USER"                           # keep it running after you log out
systemctl --user enable --now podman-auto-update.timer   # optional: daily updates
journalctl --user -u superawesomemagicalmusic            # the admin's first password is here
```

(As root, the same without `--user`, and no linger.)

### Moving to another machine

Everything is in the data volume. Stop the server so the database is still, then copy it:

```sh
# On the old machine (Docker; with Podman, `podman volume export samm-data -o samm-data.tar`):
docker stop superawesomemagicalmusic
docker run --rm -v samm-data:/data -v "$PWD":/out alpine tar cf /out/samm-data.tar -C /data .
# On the new one, before the first start:
podman volume create samm-data
podman volume import samm-data samm-data.tar
```

Accounts, playlists, saved sign-ins and the admin's password come along. If the address
changes, set the new `SAMM_PUBLIC_URL`, update the single sign-on redirect URI at your identity
provider, and sign the phones in again with the new address.

Open http://your-server:8080 and sign in as `admin` with the password from the log (or set
`SAMM_ADMIN_PASSWORD` before the first start). Then, under **Admin**:

1. **Shared sources**: add your Jellyfin (and Lidarr…). YouTube Music and Radio are shared
   already. SiriusXM is best added by each person in their own Settings, with their own account.
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

Set `SAMM_PUBLIC_URL` and `SAMM_TRUST_PROXY=true`, don't buffer responses (songs stream), and
let WebSockets through: open web players and phones keep one open at `/api/connect/ws` to see and
control each other. Caddy needs nothing special:

```
music.example.com {
    reverse_proxy superawesomemagicalmusic:8080
}
```

nginx:

```nginx
# In the http block:
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

# In the server block:
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
}
```

## Look after it

- **Back up** the data folder: `samm.db` (accounts, playlists, sources) and `secret.key`. Without
  the key, saved sign-ins (Jellyfin tokens, SiriusXM passwords, API keys) can't be read and must be entered
  again; keep it as private as a password.
- **Update**: `docker pull ghcr.io/603madison/superawesomemagicalmusic:latest` and recreate the
  container (with Quadlet: `podman auto-update`, or pull and `systemctl --user restart
  superawesomemagicalmusic`). The database updates itself. Each release is also tagged with its
  version (`:1.17.0`), to stay on one or go back.
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
