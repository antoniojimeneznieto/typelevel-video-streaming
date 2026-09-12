FROM node:22-bookworm-slim AS node

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_3.3.8 AS build
# The launcher loads sbt 2.0.7 from project/build.properties.
USER root
COPY --from=node /usr/local/ /usr/local/

WORKDIR /app
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2 \
    sbt --batch frontend/update

WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/index.html frontend/main.js frontend/vite.config.js ./
COPY frontend/public ./public
COPY frontend/src/main ./src/main

ARG VITE_IDENTITY_API_URL=/api/identity
ARG VITE_CATALOG_API_URL=/api/catalog
ARG VITE_PLAYBACK_API_URL=/api/playback

RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2 \
    npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine-slim AS runtime
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /app/frontend/dist/ /usr/share/nginx/html/
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
