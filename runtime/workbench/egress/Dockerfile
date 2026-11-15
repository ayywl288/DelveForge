FROM alpine@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
RUN apk add --no-cache squid=7.6-r0 nftables=1.1.6-r1 dnscrypt-proxy=2.1.18-r2 ca-certificates
COPY start.sh guard.nft dns.toml squid.conf /config/
RUN chmod 644 /config/dns.toml
ENTRYPOINT ["/bin/sh","/config/start.sh"]
