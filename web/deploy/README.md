# Deployment auf 3dns.eu

Schritt-für-Schritt für die Produktiv-Variante hinter nginx + systemd auf
einem Debian/Ubuntu-Host.

## Voraussetzungen

- Debian 12 oder Ubuntu 24.04+
- Node.js 22 LTS (`apt install nodejs npm` aus NodeSource-Repo oder via `n`)
- nginx mit `proxy_pass` (`apt install nginx`)
- DNS-A/AAAA-Record auf den Host (z.B. `cockpit.3dns.eu`)
- certbot (`apt install certbot python3-certbot-nginx`)

## 1. Repo deployen

```bash
sudo git clone https://git.3dns.eu/dom1nic/epf_cockpit_android.git \
    /opt/epf-cockpit-src
sudo ln -s /opt/epf-cockpit-src/web /opt/epf-cockpit-web
cd /opt/epf-cockpit-web
sudo -u root npm ci          # produktion, lock-strict
sudo -u root npm run build   # tailwind + vendor copy
```

## 2. Service-User

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin epfcockpit
sudo chown -R epfcockpit:epfcockpit /opt/epf-cockpit-src
```

Das Datenverzeichnis `/var/lib/epf-cockpit-web/` legt systemd via
`StateDirectory=` beim ersten Start automatisch an (Mode 0750, owned by
`epfcockpit:epfcockpit`).

## 3. Tokens generieren + .env anlegen

```bash
sudo install -m 0640 -o root -g epfcockpit /dev/null /etc/epf-cockpit-web.env
sudo tee -a /etc/epf-cockpit-web.env > /dev/null <<EOF
PORT=8787
BIND_ADDR=127.0.0.1
DB_PATH=/var/lib/epf-cockpit-web/epf.db
INGEST_TOKEN=$(openssl rand -hex 32)
VIEW_TOKEN=$(openssl rand -hex 32)
EOF

# Tokens für die spätere Verwendung ausgeben:
sudo grep -E '^(INGEST|VIEW)_TOKEN=' /etc/epf-cockpit-web.env
```

Speichere beide Tokens in deinem Passwort-Manager — die App braucht
`INGEST_TOKEN`, der Browser-Bookmark `VIEW_TOKEN`.

## 4. systemd-Unit

```bash
sudo cp /opt/epf-cockpit-web/deploy/epf-cockpit-web.service \
        /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now epf-cockpit-web
sudo systemctl status epf-cockpit-web
sudo journalctl -u epf-cockpit-web -f
```

Der Service hört jetzt auf `127.0.0.1:8787` (nicht öffentlich erreichbar).

## 5. nginx-Vhost

```bash
sudo cp /opt/epf-cockpit-web/deploy/nginx.conf \
        /etc/nginx/sites-available/cockpit.3dns.eu

# WebSocket-Upgrade-Map einmalig in conf.d (falls noch nicht vorhanden)
sudo tee /etc/nginx/conf.d/websocket-upgrade.conf > /dev/null <<'EOF'
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
EOF

sudo ln -s /etc/nginx/sites-available/cockpit.3dns.eu \
           /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 6. TLS via Let's Encrypt

```bash
sudo certbot --nginx -d cockpit.3dns.eu
# Folgt dem Prompt, Renewal-Cron wird automatisch eingerichtet.
```

## 7. Smoke-Test

```bash
# View-Endpoint OHNE Token → 401
curl -i https://cockpit.3dns.eu/api/state

# Mit Token → 200 + JSON
TOKEN=$(sudo grep VIEW_TOKEN= /etc/epf-cockpit-web.env | cut -d= -f2)
curl -i "https://cockpit.3dns.eu/api/state?token=$TOKEN"

# Health-Endpoint immer offen
curl -i https://cockpit.3dns.eu/healthz
```

## 8. App-Settings

In der App unter *Einstellungen → Live-Streaming an Dashboard*:

- **URL:** `wss://cockpit.3dns.eu/ws/ingest`
- **Token:** der `INGEST_TOKEN`-Wert aus `/etc/epf-cockpit-web.env`
- **Switch:** an

Im Browser (Dashboard + OBS-Widget):

- Bookmark: `https://cockpit.3dns.eu/?token=<VIEW_TOKEN>`
- OBS Browser Source: `https://cockpit.3dns.eu/obs?token=<VIEW_TOKEN>&theme=dark&accent=teal`

## Updates ausrollen

```bash
cd /opt/epf-cockpit-src
sudo -u root git pull --ff-only
cd web
sudo -u root npm ci
sudo -u root npm run build
sudo systemctl restart epf-cockpit-web
```

## Zusatz-Härtungen (optional)

### IP-Allowlist auf /ws/ingest

Im nginx-Vhost den auskommentierten `location = /ws/ingest`-Block aktivieren
und die CIDRs deines Mobilfunk-Anbieters / VPN eintragen — dann kann selbst
ein geleakter Token nicht missbraucht werden.

### Basic-Auth zusätzlich zum Token

```bash
sudo apt install apache2-utils
sudo htpasswd -c /etc/nginx/htpasswd/cockpit dom1nic
```

…und im nginx-Vhost den `auth_basic`-Block in `location /` aktivieren.

### Rate-Limit gegen Token-Bruteforce

Im `http{}`-Kontext der nginx-Hauptconfig:

```nginx
limit_req_zone $binary_remote_addr zone=cockpit:10m rate=30r/s;
```

und im `server{}`-Block `limit_req zone=cockpit burst=60 nodelay;` aktivieren.
