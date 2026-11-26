#!/usr/bin/env python3
"""KinWaypoint — configuration à distance + dépôt d'APK.

Deux fonctions dans un seul service (une image, un conteneur) :

1. Codes de configuration (docs/CONFIG-DISTANTE.md)
     GET  /kw/<token>     → 200 JSON (usage unique), 410 si déjà utilisé
     POST /api/codes      → création, en-tête X-Admin-Token

2. Dépôt d'APK
     GET  /apk/           → index HTML, du plus récent au plus ancien
     GET  /apk/index.json → la même liste en JSON
     GET  /apk/<fichier>  → téléchargement
     POST /apk/upload     → dépôt (corps = fichier brut), en-tête X-Admin-Token

Le dépôt est protégé par une authentification HTTP basique (KW_APK_USER /
KW_APK_PASS). Variables absentes = dépôt public.

Aucune dépendance externe : uniquement la bibliothèque standard.
"""
import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import shutil
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

DATA_DIR = os.environ.get("KW_DATA_DIR", "/data")
PORT = int(os.environ.get("KW_PORT", "8080"))
BASE_URL = os.environ.get("KW_BASE_URL", "https://famille.deloffre.fr/config").rstrip("/")
ADMIN_TOKEN = os.environ.get("KW_ADMIN_TOKEN", "")
DEFAULT_TTL_HOURS = int(os.environ.get("KW_TTL_HOURS", "48"))
STORE = os.path.join(DATA_DIR, "codes.json")

APK_DIR = os.path.join(DATA_DIR, "apk")
APK_INDEX = os.path.join(DATA_DIR, "apk.json")
APK_USER = os.environ.get("KW_APK_USER", "")
APK_PASS = os.environ.get("KW_APK_PASS", "")
MAX_APK_BYTES = 200 * 1024 * 1024

LOCK = threading.Lock()


def now():
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def load():
    try:
        with open(STORE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save(data):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = STORE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, STORE)


def save_json(path, data):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def expired(entry):
    try:
        limit = datetime.strptime(entry["expiresAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (KeyError, ValueError):
        return True
    return limit <= now()


def valid_child(child):
    if not isinstance(child, dict):
        return False
    url = child.get("serverUrl", "")
    device = child.get("deviceId", "")
    return url.startswith("https://") and len(device) >= 12


def valid_parent(parent):
    if not isinstance(parent, dict):
        return False
    return parent.get("apiUrl", "").startswith("https://")


# ---------------------------------------------------------------- dépôt d'APK

def load_apks():
    try:
        with open(APK_INDEX, encoding="utf-8") as f:
            meta = json.load(f)
    except (OSError, ValueError):
        meta = {}
    # Le disque fait foi : un fichier déposé à la main apparaît aussi.
    if os.path.isdir(APK_DIR):
        for name in os.listdir(APK_DIR):
            full = os.path.join(APK_DIR, name)
            if not os.path.isfile(full) or name.startswith(".") or name in meta:
                continue
            st = os.stat(full)
            meta[name] = {
                "file": name,
                "project": "KinWaypoint",
                "version": "",
                "size": st.st_size,
                "uploadedAt": iso(datetime.fromtimestamp(st.st_mtime, timezone.utc)),
                "sha256": "",
                "note": "",
            }
    items = [v for v in meta.values() if os.path.isfile(os.path.join(APK_DIR, v.get("file", "")))]
    items.sort(key=lambda x: (x.get("uploadedAt", ""), x.get("file", "")), reverse=True)
    return items


def human(size):
    value = float(size)
    for unit in ("o", "Ko", "Mo"):
        if value < 1024:
            return f"{value:.0f} {unit}" if unit == "o" else f"{value:.1f} {unit}"
        value /= 1024.0
    return f"{value:.1f} Go"


def render_index(items):
    rows = []
    for it in items:
        name = it.get("file", "")
        size = human(it.get("size", 0))
        date = (it.get("uploadedAt") or "").replace("T", " ").replace("Z", "")
        sha = (it.get("sha256") or "")[:12]
        version = it.get("version") or "—"
        note = it.get("note") or ""
        note_html = f'<span class="n">{html.escape(note)}</span>' if note else ""
        rows.append(
            f'<tr><td class="d">{html.escape(date)}</td>'
            f'<td class="v">{html.escape(version)}</td>'
            f'<td class="f"><a href="{html.escape(name)}">{html.escape(name)}</a>{note_html}</td>'
            f'<td class="s">{size}</td>'
            f'<td class="h" title="{html.escape(it.get("sha256") or "")}">{html.escape(sha) or "—"}</td></tr>'
        )
    if not rows:
        rows.append('<tr><td colspan="5" class="empty">Aucun fichier pour le moment.</td></tr>')
    return f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dépôt d'APK</title>
<style>
body {{ margin:0; padding:24px; background:#111318; color:#e7e9ee;
       font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif }}
h1 {{ font-size:19px; margin:0 0 4px; font-weight:600 }}
p.sub {{ margin:0 0 20px; color:#8b93a3; font-size:13px }}
table {{ width:100%; border-collapse:collapse; font-size:14px }}
th {{ text-align:left; font-weight:500; color:#8b93a3; font-size:12px;
      text-transform:uppercase; letter-spacing:.04em; padding:0 10px 8px }}
td {{ padding:11px 10px; border-top:1px solid #232734; vertical-align:top }}
tr:hover td {{ background:#181c24 }}
a {{ color:#7aa2ff; text-decoration:none }} a:hover {{ text-decoration:underline }}
.d {{ white-space:nowrap; color:#a8b0c0; font-variant-numeric:tabular-nums }}
.v {{ white-space:nowrap; font-weight:600 }}
.s,.h {{ white-space:nowrap; color:#8b93a3; font-variant-numeric:tabular-nums }}
.n {{ display:block; color:#8b93a3; font-size:12px; margin-top:2px }}
.empty {{ text-align:center; color:#8b93a3; padding:28px }}
</style></head><body>
<h1>Dépôt d'APK</h1>
<p class="sub">{len(items)} fichier(s) — du plus récent au plus ancien</p>
<table><thead><tr><th>Date</th><th>Version</th><th>Fichier</th><th>Taille</th><th>SHA-256</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table>
</body></html>"""


def safe_name(raw):
    name = unquote(raw or "").strip().replace("\\", "/").split("/")[-1]
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    if not name or name.startswith("."):
        return ""
    return name[:120]


class Handler(BaseHTTPRequestHandler):
    server_version = "KinWaypoint/1.0"

    def log_message(self, format, *args):
        print(f"{iso(now())} {self.client_address[0]} {format % args}", flush=True)

    def reply(self, code, payload, headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def reply_html(self, code, text):
        body = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def apk_authorized(self):
        if not APK_USER or not APK_PASS:
            return True
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            user, password = base64.b64decode(header[6:]).decode().split(":", 1)
        except (ValueError, UnicodeDecodeError):
            return False
        return hmac.compare_digest(user, APK_USER) and hmac.compare_digest(password, APK_PASS)

    def require_apk_auth(self):
        if self.apk_authorized():
            return True
        self.reply(401, {"error": "authentification requise"},
                   {"WWW-Authenticate": 'Basic realm="Depot APK", charset="UTF-8"'})
        return False

    # -------------------------------------------------------------------- GET
    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/health":
            self.reply(200, {"status": "ok"})
            return

        if path == "/apk" or path.startswith("/apk/"):
            if not self.require_apk_auth():
                return

        if path == "/apk/index.json":
            self.reply(200, {"items": load_apks()})
            return

        if path == "/apk":
            self.reply_html(200, render_index(load_apks()))
            return

        if path.startswith("/apk/"):
            name = safe_name(path[len("/apk/"):])
            full = os.path.join(APK_DIR, name)
            if not name or not os.path.isfile(full):
                self.reply(404, {"error": "fichier inconnu"})
                return
            size = os.path.getsize(full)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.end_headers()
            with open(full, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 64 * 1024)
            self.log_message("apk servi: %s (%d o)", name, size)
            return

        if path.startswith("/kw/"):
            token = path[len("/kw/"):]
            if not token or "/" in token:
                self.reply(404, {"error": "inconnu"})
                return
            with LOCK:
                data = load()
                entry = data.get(token)
                if entry is None:
                    self.reply(404, {"error": "inconnu"})
                    return
                if entry.get("used"):
                    self.log_message("token deja utilise")
                    self.reply(410, {"error": "deja utilise"})
                    return
                if expired(entry):
                    self.log_message("token expire")
                    self.reply(410, {"error": "expire"})
                    return
                entry["used"] = True
                entry["usedAt"] = iso(now())
                entry["usedFrom"] = self.client_address[0]
                data[token] = entry
                save(data)
                payload = dict(entry["payload"])
                payload["expiresAt"] = entry["expiresAt"]
            self.log_message("configuration servie (usage unique)")
            self.reply(200, payload)
            return

        self.reply(404, {"error": "route inconnue"})

    # ------------------------------------------------------------------- POST
    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/apk/upload":
            if not ADMIN_TOKEN or self.headers.get("X-Admin-Token") != ADMIN_TOKEN:
                self.log_message("refus depot apk (jeton admin)")
                self.reply(403, {"error": "interdit"})
                return
            self.upload_apk()
            return

        if path != "/api/codes":
            self.reply(404, {"error": "route inconnue"})
            return

        if not ADMIN_TOKEN or self.headers.get("X-Admin-Token") != ADMIN_TOKEN:
            self.log_message("refus admin (jeton absent ou invalide)")
            self.reply(403, {"error": "interdit"})
            return

        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"error": "corps JSON invalide"})
            return

        mode = body.get("mode")
        child = body.get("child")
        parent = body.get("parent")
        if mode == "child" and not valid_child(child):
            self.reply(400, {"error": "child.serverUrl doit etre en https et child.deviceId >= 12 caracteres"})
            return
        if mode == "parent" and not valid_parent(parent):
            self.reply(400, {"error": "parent.apiUrl doit etre en https"})
            return
        if mode not in ("child", "parent"):
            self.reply(400, {"error": "mode doit valoir 'child' ou 'parent'"})
            return

        ttl = int(body.get("ttlHours", DEFAULT_TTL_HOURS))
        token = secrets.token_urlsafe(32)
        expires = now() + timedelta(hours=ttl)

        payload = {"version": 1, "mode": mode}
        if body.get("label"):
            payload["label"] = str(body["label"])[:80]
        if mode == "child":
            payload["child"] = {
                "serverUrl": child["serverUrl"],
                "deviceId": child["deviceId"],
                "intervalSeconds": int(child.get("intervalSeconds", 60)),
            }
        else:
            section = {"apiUrl": parent["apiUrl"]}
            if parent.get("trackUrl"):
                section["trackUrl"] = parent["trackUrl"]
            if parent.get("email"):
                section["email"] = parent["email"]
            payload["parent"] = section

        with LOCK:
            data = load()
            data[token] = {
                "payload": payload,
                "createdAt": iso(now()),
                "expiresAt": iso(expires),
                "used": False,
            }
            save(data)

        url = f"{BASE_URL}/kw/{token}"
        self.log_message(f"code cree mode={mode} ttl={ttl}h")
        self.reply(201, {"url": url, "token": token, "expiresAt": iso(expires)})

    def upload_apk(self):
        name = safe_name(self.headers.get("X-Filename", ""))
        if not name or not name.lower().endswith(".apk"):
            self.reply(400, {"error": "en-tete X-Filename requis, extension .apk"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_APK_BYTES:
            self.reply(400, {"error": f"taille invalide (max {MAX_APK_BYTES // 1024 // 1024} Mo)"})
            return

        os.makedirs(APK_DIR, exist_ok=True)
        target = os.path.join(APK_DIR, name)
        tmp = target + ".part"
        digest = hashlib.sha256()
        remaining = length
        with open(tmp, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                f.write(chunk)
                digest.update(chunk)
                remaining -= len(chunk)
        if remaining > 0:
            os.unlink(tmp)
            self.reply(400, {"error": "envoi incomplet"})
            return
        os.replace(tmp, target)

        entry = {
            "file": name,
            "project": self.headers.get("X-Project", "KinWaypoint")[:40],
            "version": self.headers.get("X-Version", "")[:40],
            "size": length,
            "uploadedAt": iso(now()),
            "sha256": digest.hexdigest(),
            "note": self.headers.get("X-Note", "")[:200],
        }
        with LOCK:
            try:
                with open(APK_INDEX, encoding="utf-8") as f:
                    meta = json.load(f)
            except (OSError, ValueError):
                meta = {}
            meta[name] = entry
            save_json(APK_INDEX, meta)

        self.log_message(f"apk depose: {name} ({length} o)")
        self.reply(201, {"ok": True, "file": name, "url": f"{BASE_URL}/apk/{name}", "sha256": entry["sha256"]})


if __name__ == "__main__":
    print(f"KinWaypoint sur :{PORT} | base={BASE_URL} | data={DATA_DIR}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
