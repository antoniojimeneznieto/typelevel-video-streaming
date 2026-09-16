FROM alpine:3.23

RUN apk add --no-cache bash ca-certificates coreutils curl jq postgresql-client

WORKDIR /app

COPY wait-playback-projections.sh ./wait-playback-projections.sh

USER 1000:1000

ENTRYPOINT ["/bin/bash", "/app/wait-playback-projections.sh"]
