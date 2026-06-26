# Multi-stage: build the client jar with JDK 17 (so nothing Java touches the
# host), then serve it as a CheerpJ web app via nginx.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src ./src
RUN mvn -q -B clean package
# Produces target/rs317-client.jar (finalName=rs317-client, Main-Class=Game).

# ---- serve stage ----
FROM nginx:alpine
COPY --from=build /src/target/rs317-client.jar /usr/share/nginx/html/rs317-client.jar
COPY web/index.html            /usr/share/nginx/html/index.html
COPY web/rspy-ws-bridge.js     /usr/share/nginx/html/rspy-ws-bridge.js
COPY web/rspy-audio-bridge.js  /usr/share/nginx/html/rspy-audio-bridge.js
COPY web/nginx.conf            /etc/nginx/conf.d/default.conf

# General MIDI SoundFont for the browser MIDI player (rspy-audio-bridge.js fetches
# it from /gm.sf2). TimGM6mb.sf2 is a small (~6MB) complete GM SoundFont by Tim
# Brechbill (GPL v2), the one MuseScore 1.0 shipped with. The js-synthesizer
# library itself is loaded from the jsDelivr CDN (see web/index.html), so only
# the soundfont needs vendoring here.
RUN apk add --no-cache curl \
 && curl -fL -o /usr/share/nginx/html/gm.sf2 \
      https://raw.githubusercontent.com/arbruijn/TimGM6mb/master/TimGM6mb.sf2 \
 && apk del curl
