# SuperAwesomeMagicalMusic Tuner

Local FM and HD Radio for your music server, like an HDHomeRun for the radio. Plug one or more
RTL-SDR USB dongles into a Raspberry Pi (or any amd64 or arm64 machine), run the tuner in a
container, and the stations it hears play everywhere the music server does, with what's on:

- **FM in stereo**, with RDS: the station's name, its RadioText, the song and artist (RadioText+),
  and in the Americas its call letters.
- **HD Radio** (NRSC-5, in the US): HD1, HD2, HD3… as stations of their own, with the station's
  name and slogan, the song, artist and album, the station's logo and the album art it sends.
- **A signal score** for every station, so the music server can play the clearer copy of a
  station that's also on the internet: the tuner while it comes in clearly, the internet while it's
  static (or every tuner is busy).

One dongle tunes one frequency at a time. Everyone listening to the same station shares it; HD
Radio programs on the same frequency (HD1 and HD2) share one dongle too. A station nobody's
listening to stops after a short wait (20 seconds), so the dongles only work (and use power) while
someone listens.

It also answers like an HDHomeRun (`/discover.json`, `/lineup.json`), and its stations are plain
AAC streams any player can open.

## What you need

- A Raspberry Pi 4 or 5 (64-bit Raspberry Pi OS), or any amd64 machine. A Pi 4 manages two dongles
  playing HD Radio; a Pi 3 is fine for FM but slow for HD Radio.
- RTL-SDR dongles (an RTL-SDR Blog V3 or V4, a Nooelec NESDR…), one for each station you want to
  play at the same time, and an FM antenna (even a wire about 75 cm long helps a lot).
- Docker or Podman.

## Set up the Pi

Linux sees an RTL-SDR as a TV tuner and claims it for its TV driver, which keeps everything else
away from it. Stop that, once, on the machine the dongles are plugged into:

```sh
echo 'blacklist dvb_usb_rtl28xxu' | sudo tee /etc/modprobe.d/blacklist-rtl-sdr.conf
sudo modprobe -r dvb_usb_rtl28xxu    # or reboot
```

The tuner runs as an ordinary user (not root) in its container, so the dongles need to be usable by
the `plugdev` group. Raspberry Pi OS and Debian do that when the `rtl-sdr` package is installed
(`sudo apt install rtl-sdr`), or add the rule yourself:

```sh
echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0bda", ATTRS{idProduct}=="2838", GROUP="plugdev", MODE="0660"
SUBSYSTEMS=="usb", ATTRS{idVendor}=="0bda", ATTRS{idProduct}=="2832", GROUP="plugdev", MODE="0660"' | sudo tee /etc/udev/rules.d/60-rtl-sdr.rules
sudo udevadm control --reload && sudo udevadm trigger
```

## Run it

With Docker:

```sh
docker run -d --name samm-tuner --restart unless-stopped \
  --device /dev/bus/usb --group-add plugdev \
  -p 5004:5004 -v samm-tuner:/data \
  -e TUNER_REGION=US \
  ghcr.io/603madison/superawesomemagicalmusic-tuner:latest
```

With Podman (rootless), `--group-add keep-groups` passes your own `plugdev` membership in:

```sh
podman run -d --name samm-tuner \
  --device /dev/bus/usb --group-add keep-groups \
  -p 5004:5004 -v samm-tuner:/data \
  -e TUNER_REGION=US \
  ghcr.io/603madison/superawesomemagicalmusic-tuner:latest
```

Then open `http://your-pi:5004`, press **Scan for stations** (a few minutes: it sweeps the band,
then listens to each station it finds for its name and HD Radio), and play one.

Check the dongles are seen with `docker exec samm-tuner /opt/tuner/bin/samm-tuner devices`. Plugging
one in later needs a restart of the container.

### Compose

```yaml
services:
  tuner:
    image: ghcr.io/603madison/superawesomemagicalmusic-tuner:latest
    restart: unless-stopped
    devices:
      - /dev/bus/usb:/dev/bus/usb
    group_add:
      - plugdev
    ports:
      - "5004:5004"
    volumes:
      - samm-tuner:/data
    environment:
      TUNER_REGION: US
      TUNER_TOKEN: change-me   # optional: then the music server needs it too
      TZ: America/New_York

volumes:
  samm-tuner:
```

### Podman, started by systemd

Save as `~/.config/containers/systemd/samm-tuner.container` (Podman 4.4 and later):

```ini
[Unit]
Description=SuperAwesomeMagicalMusic Tuner

[Container]
Image=ghcr.io/603madison/superawesomemagicalmusic-tuner:latest
ContainerName=samm-tuner
AddDevice=/dev/bus/usb
PodmanArgs=--group-add keep-groups
PublishPort=5004:5004
Volume=samm-tuner:/data
Environment=TUNER_REGION=US
AutoUpdate=registry

[Service]
Restart=always

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload && systemctl --user start samm-tuner
loginctl enable-linger "$USER"
```

### More than one dongle

Plug them in and the tuner uses every one it finds (by index, in the order the system lists them).
To pick them, or to keep them in a fixed order whatever port they're in, give each a serial number
of its own (with nothing else using it: `rtl_eeprom -d 0 -s fm-attic`, then unplug and replug) and
list them:

```sh
-e TUNER_DEVICES=fm-attic,fm-garage
```

Use serial numbers that aren't just digits: rtl-sdr reads a number as an index.

## Add it to the music server

In the web player, **Settings › Sources › Add › FM tuner** (or **Admin › Shared sources** to share
it with everyone): the tuner's address (`http://your-pi:5004`) and, if you set one, its token.

Its stations then show up with the other radio stations, at the top of **Radio › Local radio**
(marked FM or HD2…), in searches, and in the apps and Android Auto, and play like any station. What's
on comes from the broadcast: the song and artist, and for HD Radio the album and its cover (else the
cover of your own copy of the song, from your library or a streaming service), with lyrics where
the player shows them.

**The clearer copy plays.** A station that's on the tuner and also on the internet (matched by its
call letters, or by its name and frequency outside the Americas) is listed once. It starts on the
tuner unless the tuner last heard it as static, and the music server keeps an ear on the tuner's
signal while it plays: static for six seconds and it crossfades to the internet stream; clear again
for twenty seconds and it crossfades back. If every tuner is busy, it plays from the internet.
(This needs ffmpeg on the music server, which its container image has; without it, the copy is
chosen once, when the station starts.)

## The signal score

Each station has a score from 0 (static) to 1 (clear), with a few words on what it's made of:

- **FM**: between the top of the audio (15 kHz) and the stereo pilot (19 kHz) a station sends
  nothing, so whatever's there is noise. The tuner compares it with the pilot (always sent at the
  same level): about 55 dB over the noise is clear stereo, 20 dB is static even in mono. RDS errors
  pull the score down too. Below about 40 dB the tuner blends to mono, which is much quieter.
- **HD Radio**: nrsc5's modulation error ratio (MER: 3 dB and below decodes nothing, 12 dB is solid),
  its bit error rate, and how many audio packets were lost.

The score is measured while a station plays (and at the scan), and kept for when it doesn't.
These thresholds come from how FM and HD Radio behave rather than from many real dongles; if a
station you'd call clear scores low (or the other way round), say so in an issue with what the
tuner's page shows for it.

## Settings

Environment variables (all optional):

| Variable | Default | |
| --- | --- | --- |
| `TUNER_REGION` | `US` | `US` for the Americas (87.9–107.9 MHz every 200 kHz, 75 µs de-emphasis, RBDS call letters, HD Radio), anything else for elsewhere (87.5–108 MHz every 100 kHz, 50 µs). |
| `TUNER_DEVICES` | every dongle found | Dongles by index or serial number, e.g. `0,1` or `fm-attic,fm-garage`. |
| `TUNER_TOKEN` | | Required by every request when set: as `Authorization: Bearer …`, or `?token=…` for players that can't send a header. Without it the tuner is open to your network. |
| `TUNER_GAIN` | automatic | Tuner gain in dB (e.g. `33.8`). Lower it if a strong local station overloads the dongle. |
| `TUNER_PPM` | `0` | The dongle's frequency error. Cheap dongles can be 50 ppm out, which matters most to HD Radio. |
| `TUNER_GRACE_SECONDS` | `20` | How long a station plays on after its last listener leaves. |
| `TUNER_BITRATE` | `160` | AAC bitrate for listeners, in kbps. |
| `TUNER_NAME` | SuperAwesomeMagicalMusic Tuner | What it's called (HDHomeRun apps show it). |
| `TUNER_PORT` | `5004` | |
| `TUNER_DATA_DIR` | `/data` | The station list (`lineup.json`) and station logos. |

## The API

Everything is JSON unless it's audio or an image. With a token set, send it with every request.

| | |
| --- | --- |
| `GET /api/status` | The tuners (what each is doing), the scan, the region. |
| `GET /api/stations` | Every station: name, call letters, what's on, signal, logo and art URLs, whether it's playing. |
| `GET /api/stations/{id}` | One station (`97.1`, or `97.1-hd2` for HD2). |
| `GET /api/stations/{id}/stream` | The station's sound, AAC (ADTS). `503` when every tuner is busy. |
| `GET /api/stations/{id}/logo`, `/art` | The station's logo, the album art it's sending (HD Radio). |
| `POST /api/stations` | Adds a station by hand: `{"frequency": "97.1", "name": "…", "hdProgram": 2}`. |
| `DELETE /api/stations/{id}` | Removes a station. |
| `POST /api/scan`, `GET /api/scan`, `DELETE /api/scan` | Starts a scan, says how it's going, cancels it. |
| `GET /discover.json`, `/lineup.json`, `/lineup_status.json` | HDHomeRun-style discovery and lineup. |

## Troubleshooting

- **"held by the kernel's TV tuner driver"**: blacklist `dvb_usb_rtl28xxu` (above) and unplug the dongle.
- **"Couldn't open dongle"**: the container can't see it (`--device /dev/bus/usb`), or can't use it
  (the `plugdev` rule and `--group-add`; as a last resort, `--user root`).
- **The scan finds nothing**: check the antenna; try `TUNER_GAIN=40`.
- **HD Radio stations aren't found, or drop out**: HD Radio needs a stronger signal than FM. A better
  antenna helps most; so does setting `TUNER_PPM` for your dongle.

`docker logs samm-tuner` says what each program (rtl_fm, redsea, rtl_sdr, nrsc5, ffmpeg) is doing,
and why one stopped; the tuner starts a stopped one again by itself.

## How it works

The tuner is a small Kotlin server that runs the standard tools and ties them together:

- **FM**: `rtl_fm` demodulates to the multiplex signal (171 kHz), which goes to
  [redsea](https://github.com/windytan/redsea) for RDS, to the tuner's own stereo decoder (a
  phase-locked loop on the 19 kHz pilot recovers the 38 kHz stereo subcarrier, then low-pass,
  blend and de-emphasis) and signal meter, and on to `ffmpeg` for AAC.
- **HD Radio**: `rtl_sdr` captures the signal once and shares it with one
  [nrsc5](https://github.com/theori-io/nrsc5) for each program being listened to; nrsc5's log gives
  the names, tags, signal and the files it saves (logos and covers).
- **Scan**: `rtl_power` sweeps the band; each signal standing out of the noise gets a few seconds
  on FM (for its RDS) and on HD Radio.

nrsc5 calls saving the station's files insecure (names come from the broadcast): the tuner keeps
them in a folder of their own, runs as an ordinary user, and only serves images it was told about.

To build the image yourself, from the repository root: `docker build -f tuner/Dockerfile -t samm-tuner .`
(redsea and nrsc5 are built from source at pinned releases). Images are published for
`linux/amd64` and `linux/arm64`.

## Develop

```sh
SAMM_SERVER_ONLY=1 ./gradlew :tuner:test        # parsers, DSP, and the whole tuner with stand-in tools
SAMM_SERVER_ONLY=1 ./gradlew :tuner:installDist
TUNER_DATA_DIR=./data tuner/build/install/samm-tuner/bin/samm-tuner
```

The tests need no dongle: stand-ins for rtl_fm, redsea, rtl_sdr, nrsc5, rtl_power and ffmpeg (small
shell scripts printing what the real ones would) run the tuner end to end.
