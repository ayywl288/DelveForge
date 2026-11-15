set -eu
peer=$(getent hosts dev | awk '/^[0-9.]+ / {print $1;exit}')
edge=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}')
test -n "$peer" && test -n "$edge"
socat TCP4-LISTEN:8140,bind="$edge",reuseaddr,fork TCP4:dev:8400 &
exec socat TCP4-LISTEN:8141,bind="$edge",reuseaddr,fork TCP4:dev:8401
