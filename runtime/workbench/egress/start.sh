#!/bin/sh
set -eu
peer=$(getent hosts dev | awk '/^[0-9.]+ / {print $1;exit}')
test -n "$peer"
bind=$(ip -4 route get "$peer" | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}')
test -n "$bind"
nft -f /config/guard.nft
nft -f /host/addresses.nft
dnscrypt-proxy -config /config/dns.toml &
sed "s/@BIND@/$bind/" /config/squid.conf > /tmp/squid.conf
exec squid -N -d1 -f /tmp/squid.conf
