# The SuperAwesomeMagicalMusic server, with its web player. Build from the repository root:
#   docker build -t superawesomemagicalmusic .        (or: podman build -t superawesomemagicalmusic .)
# See server/README.md for running it.

# The web player (built once, whatever platform the image is for).
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# The server (Java bytecode, so also built once).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS server
WORKDIR /src
# Leaves the Android app out of the build: it needs the Android SDK.
ENV SAMM_SERVER_ONLY=1
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Only for the version number, which the whole project shares.
COPY app/build.gradle.kts app/
COPY core/ core/
COPY server/ server/
COPY --from=web /src/web/dist web/dist
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g :server:installDist

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ffmpeg tini \
    && addgroup -S -g 1000 samm && adduser -S -D -H -u 1000 -G samm samm \
    && mkdir -p /data && chown samm:samm /data
COPY --from=server /src/server/build/install/samm-server /opt/samm
ENV SAMM_DATA_DIR=/data \
    SAMM_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
USER samm
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD wget -qO /dev/null http://127.0.0.1:8080/api/health || exit 1
LABEL org.opencontainers.image.title="SuperAwesomeMagicalMusic" \
      org.opencontainers.image.description="Your own music streaming service: Jellyfin, Qobuz, YouTube Music and more, with accounts and single sign-on" \
      org.opencontainers.image.licenses="GPL-3.0-only"
ENTRYPOINT ["/sbin/tini", "--", "/opt/samm/bin/samm-server"]
