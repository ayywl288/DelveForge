set -eu
umask 077
cd /keys
mkdir -p gateway client
test ! -e client/client.key
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj /CN=DelveForge-local -keyout ca.key -out ca.crt
for name in server client; do
  case "$name" in server) dest=gateway; usage=serverAuth ;; client) dest=client; usage=clientAuth ;; esac
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$dest/$name.key"
  openssl req -new -key "$dest/$name.key" -subj "/CN=$name" -out "$name.csr"
  printf 'basicConstraints=CA:FALSE\nextendedKeyUsage=%s\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n' "$usage" > "$name.ext"
  openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile "$name.ext" -out "$dest/$name.crt"
  cp ca.crt "$dest/ca.crt"
done
rm ca.key server.csr client.csr server.ext client.ext ca.srl
