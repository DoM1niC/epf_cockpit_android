#!/usr/bin/env bash
# deploy.sh — rollt web/ per SSH auf den Produktiv-Host aus.
#
# Nutzung (aus beliebigem Verzeichnis):
#   web/deploy/deploy.sh              # Standard-Ziel: webapps:/opt/epf-cockpit-web
#   web/deploy/deploy.sh --dry-run    # nur anzeigen, welche Dateien sich ändern würden
#   DEPLOY_HOST=host DEPLOY_DIR=/srv/epf DEPLOY_SERVICE=epf-cockpit-web web/deploy/deploy.sh
#
# Ablauf:
#   1. lokal: Tailwind-CSS bauen (public/dist ist gitignored, Vendor-Files liegen im Repo)
#   2. remote: Snapshot des bisherigen Codes + konsistentes SQLite-Backup
#      (`sqlite3 .backup`, läuft auch bei aktivem WAL) nach $DEPLOY_DIR/.backups/
#   3. src/ public/ scripts/ deploy/ + package*.json per tar über SSH in ein
#      Staging-Verzeichnis, dann Austausch (entfernte Dateien verschwinden mit) —
#      data/, .env und node_modules auf dem Server werden nie angefasst
#   4. `npm ci --omit=dev` nur wenn sich package-lock.json geändert hat, und zwar
#      mit der Node-Binary aus der systemd-Unit (sonst baut better-sqlite3 gegen
#      eine falsche Node-ABI, z.B. wenn nvm im SSH-PATH vorne steht)
#   5. Service neu starten + /healthz prüfen; schlägt das fehl, wird automatisch
#      auf den Snapshot zurückgerollt
#
# Voraussetzungen: lokal npm, tar, ssh; remote tar, sqlite3, curl, sha256sum und
# die systemd-Unit $DEPLOY_SERVICE.

set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-webapps}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/epf-cockpit-web}"
DEPLOY_SERVICE="${DEPLOY_SERVICE:-epf-cockpit-web}"
KEEP_BACKUPS="${KEEP_BACKUPS:-5}"

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unbekannte Option: $arg" >&2; exit 2 ;;
  esac
done

WEB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$WEB_DIR"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# --- 1. lokal bauen -----------------------------------------------------------
if [[ ! -d node_modules ]]; then
  log "node_modules fehlt — npm ci"
  npm ci --no-audit --no-fund
fi
log "Baue CSS (Tailwind)"
npm run --silent build:css
for f in public/dist/app.css public/vendor/uplot/uPlot.iife.min.js public/vendor/leaflet/leaflet.js; do
  [[ -s "$f" ]] || { echo "FEHLER: $f fehlt — erst 'npm run build' ausführen" >&2; exit 1; }
done

ITEMS=(src public scripts deploy package.json package-lock.json README.md .env.example)

# Prüfsummen-Liste "sha256  pfad" der auszuliefernden Dateien
local_sums() {
  find "${ITEMS[@]}" -type f ! -name '.DS_Store' ! -name '*.log' -print0 \
    | sort -z | xargs -0 sha256sum
}

if (( DRY_RUN )); then
  log "Dry-Run: Vergleich mit $DEPLOY_HOST:$DEPLOY_DIR"
  REMOTE_SUMS="$(ssh "$DEPLOY_HOST" "cd '$DEPLOY_DIR' && find ${ITEMS[*]} -type f -print0 2>/dev/null | sort -z | xargs -0 -r sha256sum" || true)"
  awk 'NR==FNR { r[$2]=$1; next }
       { l[$2]=$1; if (!($2 in r)) print "  + " $2; else if (r[$2]!=$1) print "  ~ " $2 }
       END { for (f in r) if (!(f in l)) print "  - " f }' \
    <(printf '%s\n' "$REMOTE_SUMS") <(local_sums) | sort -k2
  exit 0
fi

TS="$(date +%Y%m%d-%H%M%S)"

# --- 2. remote vorbereiten: Snapshot + DB-Backup -------------------------------
log "Snapshot + DB-Backup auf $DEPLOY_HOST"
ssh "$DEPLOY_HOST" bash -s -- "$(printf '%q ' "$DEPLOY_DIR" "$TS")" <<'REMOTE'
set -euo pipefail
DIR="$1"; TS="$2"
cd "$DIR"
mkdir -p .backups
# Code-Snapshot für den Rollback (ohne Daten/Module/Secrets)
tar czf ".backups/code-$TS.tgz" --ignore-failed-read \
  src public scripts deploy package.json package-lock.json 2>/dev/null || true
sha256sum package-lock.json 2>/dev/null | cut -d' ' -f1 > ".backups/lock-$TS.sha" || true
# DB-Pfad aus .env (relativ zum Arbeitsverzeichnis), Default ./data/epf.db
DB="$(sed -n 's/^DB_PATH=//p' .env 2>/dev/null | tail -1)"; DB="${DB:-./data/epf.db}"
if [[ -f "$DB" ]]; then
  sqlite3 "$DB" ".backup '.backups/epf-$TS.db'"
  echo "DB-Backup: $DIR/.backups/epf-$TS.db ($(du -h ".backups/epf-$TS.db" | cut -f1))"
fi
REMOTE

# --- 3. Code übertragen (Staging) ---------------------------------------------
log "Übertrage Code nach $DEPLOY_HOST:$DEPLOY_DIR/.incoming"
tar czf - --exclude='.DS_Store' --exclude='*.log' "${ITEMS[@]}" \
  | ssh "$DEPLOY_HOST" "set -e; cd '$DEPLOY_DIR'; rm -rf .incoming; mkdir .incoming; tar xzf - --no-same-owner -C .incoming"

# --- 4./5. Abhängigkeiten, Neustart, Healthcheck, ggf. Rollback ---------------
log "Abhängigkeiten + Neustart + Healthcheck"
# ssh fügt alle Argumente zu EINEM Kommando-String zusammen, den die Remote-Shell
# neu zerlegt — daher jedes Argument explizit quoten.
ssh "$DEPLOY_HOST" bash -s -- "$(printf '%q ' "$DEPLOY_DIR" "$TS" "$DEPLOY_SERVICE" "$KEEP_BACKUPS" "${ITEMS[*]}")" <<'REMOTE'
set -euo pipefail
DIR="$1"; TS="$2"; SERVICE="$3"; KEEP="$4"; read -r -a ITEMS <<< "$5"
cd "$DIR"

# Node-Binary des Services (ExecStart=… path=/usr/bin/node …) — npm muss mit
# derselben Node laufen, sonst passt das better-sqlite3-Binary nicht.
NODE_BIN="$(systemctl show "$SERVICE" -p ExecStart --value | sed -n 's/.*path=\([^ ;]*\).*/\1/p' | head -1)"
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
NODE_DIR="$(dirname "$NODE_BIN")"
export PATH="$NODE_DIR:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
echo "Node: $NODE_BIN ($("$NODE_BIN" -v))"

PORT="$(sed -n 's/^PORT=//p' .env 2>/dev/null | tail -1)"; PORT="${PORT:-8787}"
MODULES_SWAPPED=0

healthy() {
  for _ in $(seq 1 20); do
    if curl -fs -o /dev/null "http://127.0.0.1:$PORT/healthz"; then return 0; fi
    sleep 0.5
  done
  return 1
}

rollback() {
  echo "FEHLER: $1 — Rollback auf Snapshot $TS" >&2
  journalctl -u "$SERVICE" -n 30 --no-pager >&2 || true
  rm -rf src public scripts deploy
  tar xzf ".backups/code-$TS.tgz"
  if [[ "$MODULES_SWAPPED" == 1 ]]; then
    rm -rf node_modules
    mv ".backups/node_modules-$TS" node_modules
  fi
  systemctl restart "$SERVICE"
  if healthy; then
    echo "Rollback erfolgreich — alte Version läuft wieder." >&2
  else
    echo "Rollback-Healthcheck ebenfalls fehlgeschlagen!" >&2
  fi
  exit 1
}

# Staging gegen den laufenden Stand tauschen (Service läuft bis zum Restart
# aus dem Speicher weiter).
for item in "${ITEMS[@]}"; do
  [[ -e ".incoming/$item" ]] || { echo "FEHLER: .incoming/$item fehlt" >&2; exit 1; }
  rm -rf "$item"
  mv ".incoming/$item" "$item"
done
rm -rf .incoming

OLD_LOCK="$(cat ".backups/lock-$TS.sha" 2>/dev/null || true)"
NEW_LOCK="$(sha256sum package-lock.json | cut -d' ' -f1)"
if [[ "$OLD_LOCK" != "$NEW_LOCK" || ! -d node_modules ]]; then
  echo "package-lock.json geändert — npm ci --omit=dev"
  if [[ -d node_modules ]]; then mv node_modules ".backups/node_modules-$TS"; MODULES_SWAPPED=1; fi
  npm ci --omit=dev --no-audit --no-fund || rollback "npm ci fehlgeschlagen"
else
  echo "package-lock.json unverändert — node_modules bleibt"
fi

systemctl restart "$SERVICE"
if healthy && systemctl is-active --quiet "$SERVICE"; then
  echo "OK: $SERVICE läuft, /healthz antwortet (Port $PORT)"
  if [[ "$MODULES_SWAPPED" == 1 ]]; then rm -rf ".backups/node_modules-$TS"; fi
else
  rollback "Healthcheck fehlgeschlagen"
fi

# Alte Backups aufräumen (je Typ die neuesten $KEEP behalten)
for pat in 'code-*.tgz' 'epf-*.db' 'lock-*.sha'; do
  ls -1t .backups/$pat 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
done
REMOTE

log "Deploy fertig: $DEPLOY_HOST:$DEPLOY_DIR ($TS)"
