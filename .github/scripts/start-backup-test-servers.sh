#!/usr/bin/env bash
# Starts the servers that the backup tests upload to: OpenSSH for SFTP, pure-ftpd for FTPS with
# explicit TLS, vsftpd for FTPS with implicit TLS, and rclone for FTP and WebDAV over HTTP and
# HTTPS. rclone also serves FTPS with implicit TLS but unencrypted file transfers, which the app
# must refuse. All listen on 127.0.0.1, which an Android emulator reaches at
# 10.0.2.2. The connection details are written to $GITHUB_ENV for the following steps.
set -euo pipefail

dir="$RUNNER_TEMP/backup-servers"
user=pipetest
password="pipe-$(openssl rand -hex 12)"
echo "::add-mask::$password"
mkdir -p "$dir"

sudo apt-get update -qq
# Installing must not start the packaged services, which would compete for port 21
echo 'exit 101' | sudo tee /usr/sbin/policy-rc.d >/dev/null
sudo chmod +x /usr/sbin/policy-rc.d
sudo apt-get install -y -qq openssh-server pure-ftpd vsftpd netcat-openbsd unzip >/dev/null
sudo rm -f /usr/sbin/policy-rc.d

# The rclone package of Ubuntu cannot serve FTP, so use an official release
rclone_version=v1.75.1
curl -fsSL -o "$dir/rclone.zip" \
  "https://downloads.rclone.org/$rclone_version/rclone-$rclone_version-linux-amd64.zip"
curl -fsSL -o "$dir/rclone.sums" "https://downloads.rclone.org/$rclone_version/SHA256SUMS"
(cd "$dir" && grep " rclone-$rclone_version-linux-amd64.zip\$" rclone.sums \
  | sed "s/ rclone-$rclone_version-linux-amd64.zip\$/ rclone.zip/" | sha256sum -c -)
unzip -q -j "$dir/rclone.zip" "*/rclone" -d "$dir/bin"
rclone="$dir/bin/rclone"
"$rclone" version

# SFTP: OpenSSH on port 2222 with password and key logins
sudo useradd --create-home --shell /bin/bash "$user"
echo "$user:$password" | sudo chpasswd
ssh-keygen -q -t ed25519 -N '' -f "$dir/id_ed25519"
ssh-keygen -q -t rsa -b 3072 -N 'key passphrase' -f "$dir/id_rsa"
sudo install -d -m 700 -o "$user" -g "$user" "/home/$user/.ssh"
cat "$dir/id_ed25519.pub" "$dir/id_rsa.pub" | sudo tee "/home/$user/.ssh/authorized_keys" >/dev/null
sudo chown "$user:$user" "/home/$user/.ssh/authorized_keys"
sudo chmod 600 "/home/$user/.ssh/authorized_keys"
sudo mkdir -p /run/sshd
sudo ssh-keygen -A
# Options given here take precedence over the configuration files
sudo /usr/sbin/sshd -p 2222 -E "$dir/sshd.log" \
  -o PasswordAuthentication=yes \
  -o PubkeyAuthentication=yes \
  -o KbdInteractiveAuthentication=no

# A self-signed certificate for the TLS servers
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
  -keyout "$dir/key.pem" -out "$dir/cert.pem" 2>/dev/null

# FTPS with explicit TLS: pure-ftpd on port 2991 with the logins of system users. It reads the
# certificate from its default location.
cat "$dir/key.pem" "$dir/cert.pem" | sudo tee /etc/ssl/private/pure-ftpd.pem >/dev/null
sudo chmod 600 /etc/ssl/private/pure-ftpd.pem
sudo /usr/sbin/pure-ftpd --daemonize --bind 127.0.0.1,2991 --login unix --noanonymous \
  --tls 1 --passiveportrange 30100:30149 --forcepassiveip 127.0.0.1

# FTPS with implicit TLS: vsftpd on port 2992, also with the logins of system users. Session
# reuse is turned off because Java's FTPS client cannot reuse TLS sessions for file transfers.
sudo mkdir -p /var/run/vsftpd/empty
sudo tee "$dir/vsftpd.conf" >/dev/null <<VSFTPD
listen=YES
listen_ipv6=NO
listen_address=127.0.0.1
listen_port=2992
background=YES
anonymous_enable=NO
local_enable=YES
write_enable=YES
pasv_enable=YES
pasv_min_port=30150
pasv_max_port=30199
pasv_address=127.0.0.1
ssl_enable=YES
implicit_ssl=YES
force_local_logins_ssl=YES
force_local_data_ssl=YES
require_ssl_reuse=NO
rsa_cert_file=$dir/cert.pem
rsa_private_key_file=$dir/key.pem
seccomp_sandbox=NO
secure_chroot_dir=/var/run/vsftpd/empty
pam_service_name=vsftpd
vsftpd_log_file=$dir/vsftpd.log
VSFTPD
sudo chown root:root "$dir/vsftpd.conf"
sudo /usr/sbin/vsftpd "$dir/vsftpd.conf"

# FTP, FTPS with implicit TLS but unencrypted file transfers, and WebDAV
mkdir -p "$dir/ftp" "$dir/ftps" "$dir/dav" "$dir/davs"
auth=(--user "$user" --pass "$password")
nohup "$rclone" serve ftp "$dir/ftp" --addr 127.0.0.1:2121 "${auth[@]}" \
  --passive-port 30000-30049 --public-ip 127.0.0.1 >"$dir/ftp.log" 2>&1 &
nohup "$rclone" serve ftp "$dir/ftps" --addr 127.0.0.1:2990 "${auth[@]}" \
  --passive-port 30050-30099 --public-ip 127.0.0.1 \
  --cert "$dir/cert.pem" --key "$dir/key.pem" >"$dir/ftps.log" 2>&1 &
nohup "$rclone" serve webdav "$dir/dav" --addr 127.0.0.1:8080 "${auth[@]}" \
  >"$dir/dav.log" 2>&1 &
nohup "$rclone" serve webdav "$dir/davs" --addr 127.0.0.1:8443 "${auth[@]}" \
  --cert "$dir/cert.pem" --key "$dir/key.pem" >"$dir/davs.log" 2>&1 &

for port in 2222 2121 2990 2991 2992 8080 8443; do
  for _ in $(seq 60); do
    if nc -z 127.0.0.1 "$port"; then
      break
    fi
    sleep 1
  done
  if ! nc -z 127.0.0.1 "$port"; then
    echo "::error::Nothing listens on port $port"
    tail -n 50 "$dir"/*.log || true
    exit 1
  fi
done

{
  echo "PIPEPIPE_TEST_SERVER_HOST=127.0.0.1"
  echo "PIPEPIPE_TEST_SERVER_USER=$user"
  echo "PIPEPIPE_TEST_SERVER_PASSWORD=$password"
  echo "PIPEPIPE_TEST_SFTP_PORT=2222"
  echo "PIPEPIPE_TEST_SFTP_KEY_ED25519=$dir/id_ed25519"
  echo "PIPEPIPE_TEST_SFTP_KEY_ED25519_BASE64=$(base64 -w0 "$dir/id_ed25519")"
  echo "PIPEPIPE_TEST_SFTP_KEY_RSA=$dir/id_rsa"
  echo "PIPEPIPE_TEST_SFTP_KEY_RSA_PASSPHRASE=key passphrase"
  echo "PIPEPIPE_TEST_FTP_PORT=2121"
  echo "PIPEPIPE_TEST_FTPS_PORT=2991"
  echo "PIPEPIPE_TEST_FTPS_IMPLICIT_PORT=2992"
  echo "PIPEPIPE_TEST_FTPS_PLAIN_DATA_PORT=2990"
  echo "PIPEPIPE_TEST_WEBDAV_PORT=8080"
  echo "PIPEPIPE_TEST_WEBDAV_HTTPS_PORT=8443"
} >>"$GITHUB_ENV"
echo "Backup test servers are running"
