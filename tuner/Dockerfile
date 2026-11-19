# The FM and HD Radio tuner, for a Raspberry Pi (arm64) or any amd64 machine with RTL-SDR
# dongles. Build from the repository root:
#   docker build -f tuner/Dockerfile -t samm-tuner .        (or: podman build -f tuner/Dockerfile …)
# See tuner/README.md for running it.

# The tuner itself (Java bytecode, so built once, whatever platform the image is for).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS tuner
WORKDIR /src
# Leaves the Android apps out of the build: they need the Android SDK.
ENV SAMM_SERVER_ONLY=1
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Only for the version number, which the whole project shares.
COPY app/build.gradle.kts app/
# Gradle wants every project in the build to be there, though only the tuner is built.
COPY core/build.gradle.kts core/
COPY server/build.gradle.kts server/
COPY tuner/ tuner/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g :tuner:installDist

# redsea (RDS) and nrsc5 (HD Radio), from source at pinned releases, checked by commit.
FROM debian:trixie-slim AS decoders
ARG REDSEA_TAG=v1.3.1
ARG REDSEA_COMMIT=4cc27df9939798e800c4ff7cb484be6d9bf68b4d
ARG NRSC5_TAG=v3.2.0
ARG NRSC5_COMMIT=a5c09723b135327aeef157770caae9aebf597547
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git build-essential pkg-config meson ninja-build cmake \
       autoconf automake libtool patch libsndfile1-dev libliquid-dev nlohmann-json3-dev libao-dev libfftw3-dev librtlsdr-dev libusb-1.0-0-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN git clone --depth 1 --branch "$REDSEA_TAG" https://github.com/windytan/redsea.git \
    && test "$(git -C redsea rev-parse HEAD)" = "$REDSEA_COMMIT" \
    && cd redsea && meson setup build --buildtype=release --prefix=/opt/radio && meson compile -C build && meson install -C build
RUN git clone --depth 1 --branch "$NRSC5_TAG" https://github.com/theori-io/nrsc5.git \
    && test "$(git -C nrsc5 rev-parse HEAD)" = "$NRSC5_COMMIT" \
    && cmake -S nrsc5 -B nrsc5/build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/radio \
    && cmake --build nrsc5/build -j"$(nproc)" && cmake --install nrsc5/build

FROM debian:trixie-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless rtl-sdr ffmpeg tini \
       libsndfile1 libliquid1 libao4 libfftw3-single3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 tuner && useradd -r -u 1000 -g tuner -G plugdev -d /data -s /usr/sbin/nologin tuner \
    && mkdir -p /data && chown tuner:tuner /data
COPY --from=decoders /opt/radio /opt/radio
COPY --from=tuner /src/tuner/build/install/samm-tuner /opt/tuner
RUN ldconfig /opt/radio/lib /opt/radio/lib/*-linux-gnu 2>/dev/null; true
ENV PATH=/opt/radio/bin:$PATH \
    LD_LIBRARY_PATH=/opt/radio/lib:/opt/radio/lib/x86_64-linux-gnu:/opt/radio/lib/aarch64-linux-gnu \
    TUNER_DATA_DIR=/data \
    TUNER_PORT=5004 \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC"
# The dongles' device files belong to the host; see the README for letting this user use them.
USER tuner
VOLUME /data
EXPOSE 5004
LABEL org.opencontainers.image.title="SuperAwesomeMagicalMusic Tuner" \
      org.opencontainers.image.description="Local FM and HD Radio from RTL-SDR dongles, with what's playing, for SuperAwesomeMagicalMusic and anything on your network" \
      org.opencontainers.image.licenses="GPL-3.0-only"
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/tuner/bin/samm-tuner"]
