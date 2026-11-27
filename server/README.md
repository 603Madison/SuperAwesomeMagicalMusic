# SuperAwesomeMagicalMusic server

Your own music streaming service. The server signs in to your music services once (Jellyfin or
a Subsonic server such as Navidrome, YouTube Music, Lidarr) and serves them to everyone you let in, with a web player for the
computer. Each person gets their own playlists and history (and a yearly **Wrapped** from it), and can add their own services.

- **Accounts**: a local *break-glass* admin made at first start, local accounts, and single
  sign-on with any OpenID Connect provider (Authentik, Keycloak, Authelia, Zitadel…).
- **Sources**: admins share sources with everyone (the house Jellyfin or Navidrome, a Lidarr);
  people add their own (their own Jellyfin, for one) and can switch shared ones off for themselves.
  Saved sign-ins are encrypted.
- **Playback**: like the Standalone app, songs play from your library (Jellyfin, or a Subsonic
  server) when it has them, else
  the best copy on a streaming service within your quality setting. ffmpeg converts on the server
  only when needed (over your bitrate cap, or a format the browser can't play).
- **Live radio**: SiriusXM channels (with what's on now, and Xtra channels) and Pandora artist
  stations through SiriusXM, each person with their own SiriusXM account; and tens of thousands of
  local and internet stations from Radio Browser, by name or near you, with song titles shown.
  Everything streams through the server, so it works from anywhere the server is reachable.
- **Local FM and HD Radio**: add a [tuner](../tuner/README.md) (a Raspberry Pi with RTL-SDR
  dongles) under Sources as an *FM tuner*, and the stations it hears are listed with the others,
  with what's on from the broadcast. A station that's also online plays from the tuner while it
  comes in clearly and from the internet while it's static, switching by itself.
- **Lidarr**: artists of songs played from outside the library are added for you.
- **Subsonic servers**: Navidrome, Gonic, Airsonic(-Advanced), Ampache, LMS, Nextcloud Music,
  Funkwhale and others that speak the [Subsonic API](http://www.subsonic.org/pages/api.jsp) are
  added under Sources as *Subsonic*, with the server's address and a username and password, or an
  API key ([OpenSubsonic](https://opensubsonic.netlify.app/) servers that give them out). The
  password is kept (encrypted), as Subsonic signs every request with it: only a salted token
  made from it is sent, unless the server can't check tokens (LDAP accounts on Navidrome, say),
  when the password itself is. Streams and covers go through the server, so the apps never see
  the credentials. Its songs are searched and played first like Jellyfin's, Listen learns them,
  and its playlists can be brought in under Playlists › Import › From your library. Songs over
  your bitrate cap are transcoded to MP3 by the Subsonic server itself.
- **Sonos and Music Assistant**: your speakers join your devices, and the music moves to them
  from any app (see [Sonos and Music Assistant](#sonos-and-music-assistant)).

## Sonos and Music Assistant

Speakers show up with your devices (the web player's **Play on a device**, the phone app's
devices), next to your browsers and phones. Choose one and the music moves there, carrying on
from the same moment; play, pause, skip, seek and turn it up from any of your devices, and see
what it's playing.

- **Sonos**: add **Sonos** under **Settings › Sources** (an admin's shared one gives everyone
  the speakers; your own, only you). Speakers are found on the network, or add one speaker's
  address (any one is enough: the rest of its household comes through it). Grouped speakers show
  as one place to play ("Living Room + Kitchen") and play together; the Sonos app shows the song,
  artist, album and cover. Songs, radio stations and your own stations play; SiriusXM's live
  channels don't yet.
- **Music Assistant** ([music-assistant.io](https://music-assistant.io), Home Assistant's music
  server): add **Music Assistant** under **Settings › Sources** with its address
  (`http://homeassistant.local:8095`) and, for Music Assistant 2.7 and later, a long-lived token
  made in its web interface. Its players (Sonos, Chromecast, AirPlay, Squeezebox, DLNA, Home
  Assistant's media players) become places to play.
- **How it plays**: a speaker can't run the web player, so the server plays the queue on it: it
  gives the speaker one song at a time and moves to the next when one ends. The speaker fetches
  each song from the server at an address made for it alone: signed, for that one song (or
  station) and that person, working for the song's length and an hour more (a station's for 12
  hours), and opening nothing else. Formats are picked for the speaker (Sonos gets FLAC, ALAC,
  AAC, MP3 or WAV as they are, and anything else converted to MP3 or FLAC).
- **The server's address for speakers**: speakers fetch songs from the server on your home
  network, at the address it has there (worked out by itself). In a container with its own
  network that's the container's address, which speakers can't reach: set **This server's
  address for speakers** on the source (or `SAMM_SPEAKER_URL`), e.g. `http://192.168.1.10:8080`
  (the computer's address and the published port).
- **In a container, finding Sonos speakers** needs the container on the home network itself
  (`--network host`, or `Network=host` with Podman), since discovery asks the whole network
  (SSDP multicast), which doesn't cross into a container's own network. Otherwise turn off
  **Find speakers on the network** and add a speaker's address: the server only needs to reach
  the speakers on port 1400, and they the server on its port. Music Assistant needs neither.

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

1. **Shared sources**: add your Jellyfin or Subsonic server (and Lidarr…). YouTube Music and Radio are shared
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

**Signing in with a code**: a device that's awkward to type on (SuperAwesomeMagicalMusic Mini on a
flip phone, or the phone app's *Sign in with a code from another device*) shows a short code. Type
it at your server's `/link` page (or the web player's Settings › Signed in on › *Sign in a device
with a code*) where you're signed in, check which device is asking, and allow it. This works with
single sign-on too, since you approve it from a sign-in you already have.

## Settings

Environment variables (all optional):

| Variable | Default | |
| --- | --- | --- |
| `SAMM_DATA_DIR` | `/data` | Where everything is kept. |
| `SAMM_PORT` | `8080` | |
| `SAMM_PUBLIC_URL` | | The address people use, e.g. `https://music.example.com`. Needed for single sign-on behind a proxy. |
| `SAMM_ORCHESTRATOR_URL` | | SuperAwesomeMagicalOrchestrator's address, e.g. `https://orchestrator.example.com`: apps on devices signed in there sign in here by themselves (see below). Also on the admin page; set here, it wins at every start. |
| `SAMM_TRUST_PROXY` | `false` | Believe `X-Forwarded-*` headers (only behind your own reverse proxy). |
| `SAMM_PROXIES` | `1` | How many proxies in a row are in front, each adding to `X-Forwarded-For` (Cloudflare, then your own proxy: `2`). Used to tell visitors apart when slowing down password guessing. |
| `SAMM_CLIENT_IP_HEADER` | | A header the proxy in front sets to the visitor's address, used instead: `CF-Connecting-IP` behind Cloudflare. Only when every way in goes through it. |
| `SAMM_ADMIN_USERNAME` | `admin` | The break-glass admin, made at first start. |
| `SAMM_ADMIN_PASSWORD` | generated | Its first password. |
| `SAMM_MAX_TRANSCODES` | `4` | Songs converted at once. |
| `SAMM_LOCAL_SOURCES` | `false` | Lets everyone's own sources (not just admins') be on the server's own machine (`localhost`). Off, nobody else can point the server at what listens only there. |
| `SAMM_SPEAKER_URL` | worked out | The server's address as Sonos speakers and Music Assistant reach it, e.g. `http://192.168.1.10:8080`, when it's in a container with its own network (see [Sonos and Music Assistant](#sonos-and-music-assistant)). A source's own setting comes first. |
| `SAMM_OIDC_ISSUER`, `SAMM_OIDC_CLIENT_ID`, `SAMM_OIDC_CLIENT_SECRET`, `SAMM_OIDC_USER_GROUP`, `SAMM_OIDC_ADMIN_GROUP`, `SAMM_OIDC_BUTTON_LABEL`, `SAMM_OIDC_SCOPES`, `SAMM_LOCAL_LOGIN` | | Single sign-on and password sign-in, if you'd rather configure them here. They're applied at every start, over the admin page. |

### Behind a reverse proxy

Set `SAMM_PUBLIC_URL` and `SAMM_TRUST_PROXY=true`, don't buffer responses (songs stream), and
let WebSockets through: open web players and phones keep one open at `/api/connect/ws` to see and
control each other. Behind Cloudflare (proxied, or a Cloudflare Tunnel) also set
`SAMM_CLIENT_IP_HEADER=CF-Connecting-IP`, so the server knows each visitor's own address.
Caddy needs nothing special:

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
  again; keep it as private as a password. `listen.db` (what Listen learned of your libraries,
  about 15 MB for every 100 songs) needn't be backed up: the server learns it again if it's gone.
- **Update**: `docker pull ghcr.io/603madison/superawesomemagicalmusic:latest` and recreate the
  container (with Quadlet: `podman auto-update`, or pull and `systemctl --user restart
  superawesomemagicalmusic`). The database updates itself. Each release is also tagged with its
  version (`:1.17.0`), to stay on one or go back.
- **Lost the admin password?**
  `docker exec superawesomemagicalmusic /opt/samm/bin/samm-server reset-admin-password` prints a
  new one (or give it one: `… reset-admin-password 'new password'`).

## Claude connector (MCP)

The server is also a [Model Context Protocol](https://modelcontextprotocol.io) server, so Claude
can look at it to help troubleshoot. In the web player, open **Settings › Claude connector** and
**Make a connector link**, then in Claude add it under **Settings › Connectors › Add custom
connector**. Claude then has these tools, all read-only:

| Tool | What it shows |
|---|---|
| `server_status` | Version, uptime, the public address, your sources (with their ids), devices connected |
| `recent_warnings` | The server's latest warnings and errors (admins only) |
| `server_log` | Everything the server logged lately: each request with its status and time, relayed SiriusXM segments and keys, SiriusXM's timeline (admins only) |
| `siriusxm_log` | SiriusXM's Xtra timeline (tunes, peeks, playlist reloads, the song heard, segments or keys the server couldn't relay, errors and stalls the web player reported) and, with `details`, what SiriusXM answered |
| `stream_test` | Fetches a song as a player would and says how the service answered and how fast (every YouTube copy) |
| `search` | Songs from all your sources, with the ids `stream_test` takes |
| `devices` | Your devices and what each is playing |
| `recent_listens` | Your listening history |

The link is the key (`https://your-server/mcp/<token>`; the token also works as a bearer token on
`/mcp`). It sees only what your account sees, opens nothing but the connector, and is listed with
your sign-ins under **Signed in on**, where it's removed. An admin can turn on **See every account**
before making the link: it then sees every account's sources, SiriusXM, devices and history (the
tools take an `account` to look at someone's search or history), for as long as its admin is one. If Claude can't reach it behind
Cloudflare, check that bot protection isn't blocking `/mcp`.

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
- Codes for signing in another device (OAuth's device authorization flow, `/api/auth/device/*`)
  work once and expire after ten minutes; the device holds a long secret of its own, and only
  someone signed in can allow it, after seeing the device's name. Wrong codes are limited per
  person and address, like wrong passwords, and devices asking too often are told to slow down.
- Signing in through SuperAwesomeMagicalOrchestrator (only when its address is set: **Admin ›
  Server › Orchestrator address**, or `SAMM_ORCHESTRATOR_URL`; otherwise `POST /api/auth/orchestrator`
  answers 404 and nothing changes). An app on a device signed in there asks the orchestrator's
  agent for a ticket for the app `music` and posts `{ticket, deviceName?}`; it gets what an app
  sign-in returns. `GET /api/auth/config` says `"orchestrator": true`. The ticket is a short-lived
  JWT the orchestrator signs (ES256), checked against the keys it publishes
  (`<orchestrator>/api/orchestrator/keys`): signature, issuer, audience (this server's public
  address, or the address the request came to), app, dates, and that it hasn't been used before.
  Whose account:
  1. The ticket carries the person's subject at the identity provider, and that's this server's
     own provider (single sign-on is on, same scheme, host and port as its issuer): the account
     with that subject, by the same rules as single sign-on here (the allowed group, the admin
     group, a new account if that's on), from the ticket's groups and name.
  2. Otherwise the account with the ticket's username, if it's an ordinary one: not the
     break-glass admin, not turned off, not tied to an identity provider.
  3. Otherwise 401 ("That sign-in didn't work here. Sign in the usual way.").

  Refusals are logged with the reason and count against the address like wrong passwords. The
  orchestrator's keys are fetched from the address the admin set (often on the home network),
  without following redirects, 256 KB at most. Admins can try an address with
  `POST /api/admin/orchestrator/test` `{url}` → `{ok, message}` (the admin page's **Test**).
- Speakers can't sign in, so the addresses they play from (`/api/speakers/stream/…`) carry a
  signed pass instead: one song or station, one person, a short time. Changing any of it breaks
  the signature; it isn't a sign-in and opens nothing else; it stops working when the person's
  account is turned off or the source is no longer theirs. Anyone on your network who sees one
  can play that song until it expires.
- A Claude connector link is a sign-in that only opens `/mcp`, where everything is read-only.
  Anyone with the link can read what the tools show (your sources' names, your history, SiriusXM's
  log); remove it under **Signed in on** if it leaks.

## Develop

```sh
./gradlew :server:test                     # tests (one needs ffmpeg installed)
cd web && npm install && npm run build     # the web player, bundled into the server
./gradlew :server:installDist
SAMM_DATA_DIR=./data server/build/install/samm-server/bin/samm-server
cd web && npm run dev                      # live-reloading web player on :5173, API from :8080
```
