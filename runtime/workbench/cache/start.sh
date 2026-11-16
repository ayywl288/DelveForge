#!/bin/sh
set -eu
nft -f /config/guard.nft
nft -f /host/addresses.nft
dnscrypt-proxy -config /config/dns.toml &
mkdir -p /cache/central
chown nginx:nginx /cache/central
exec nginx -c /cache-config/nginx.conf -g 'daemon off;'
