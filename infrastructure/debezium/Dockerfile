FROM alpine:3.23

RUN apk add --no-cache bash ca-certificates curl jq

WORKDIR /debezium
COPY register-connector.sh identity-outbox.json catalog-outbox.json ./

USER 1000:1000
ENTRYPOINT ["/bin/bash", "/debezium/register-connector.sh"]
