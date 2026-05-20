#!/bin/sh
set -eu

cert_dir=/etc/mysql/certs
mkdir -p "$cert_dir"

if [ ! -f "$cert_dir/ca.pem" ]; then
  openssl genrsa 2048 > "$cert_dir/ca-key.pem"
  openssl req -new -x509 -nodes -days 3650 \
    -key "$cert_dir/ca-key.pem" \
    -out "$cert_dir/ca.pem" \
    -subj "/CN=apk-instant-deploy-local-ca"

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$cert_dir/server-key.pem" \
    -out "$cert_dir/server-req.pem" \
    -subj "/CN=mysql"

  cat > "$cert_dir/server-ext.cnf" <<'EOF'
subjectAltName=DNS:mysql,DNS:localhost,IP:127.0.0.1
EOF

  openssl x509 -req -days 3650 \
    -in "$cert_dir/server-req.pem" \
    -CA "$cert_dir/ca.pem" \
    -CAkey "$cert_dir/ca-key.pem" \
    -set_serial 01 \
    -out "$cert_dir/server-cert.pem" \
    -extfile "$cert_dir/server-ext.cnf"
fi

chown -R mysql:mysql "$cert_dir"
chmod 600 "$cert_dir"/*-key.pem

exec docker-entrypoint.sh mysqld \
  --ssl-ca="$cert_dir/ca.pem" \
  --ssl-cert="$cert_dir/server-cert.pem" \
  --ssl-key="$cert_dir/server-key.pem"
