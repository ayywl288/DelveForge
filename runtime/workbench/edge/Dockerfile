FROM alpine@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
RUN apk add --no-cache socat=1.8.1.3-r0 openssl
COPY start.sh certs.sh /
USER 65534:65534
ENTRYPOINT ["/bin/sh","/start.sh"]
