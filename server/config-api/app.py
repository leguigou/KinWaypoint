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
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote
import urllib.request

DATA_DIR = os.environ.get("KW_DATA_DIR", "/data")
PORT = int(os.environ.get("KW_PORT", "8080"))
# Adresses publiques : à définir dans l'environnement ; à défaut, déduites de la
# requête reçue (en-têtes du reverse proxy). Aucun domaine réel dans le code.
BASE_URL = os.environ.get("KW_BASE_URL", "").rstrip("/")
ADMIN_TOKEN = os.environ.get("KW_ADMIN_TOKEN", "")
DEFAULT_TTL_HOURS = int(os.environ.get("KW_TTL_HOURS", "48"))
STORE = os.path.join(DATA_DIR, "codes.json")

APK_DIR = os.path.join(DATA_DIR, "apk")
APK_INDEX = os.path.join(DATA_DIR, "apk.json")
APK_BASE_URL = os.environ.get("KW_APK_BASE_URL", "").rstrip("/")
APK_CODE = os.environ.get("KW_APK_CODE", "azerty")
# Adresses du serveur Traccar, données à l'appli pour qu'on n'ait plus qu'un
# e-mail et un mot de passe à saisir (route publique /apk/server.json).
TRACCAR_API_URL = os.environ.get("KW_TRACCAR_API_URL", "").rstrip("/")
TRACCAR_TRACK_URL = os.environ.get("KW_TRACCAR_TRACK_URL", "").rstrip("/")
# Relais de l'« écoute en direct » (wss://…/audio) : facultatif.
AUDIO_RELAY_URL = os.environ.get("KW_AUDIO_RELAY_URL", "").rstrip("/")
COOKIE_NAME = "kwapk"
COOKIE_DAYS = 30
# Une consommation n'est pas instantanément fatale : on tolère quelques
# relectures dans une courte fenêtre (double-tap, mauvais réseau, retour arrière
# du navigateur) avant de fermer définitivement le jeton.
GRACE_MINUTES = 10
MAX_READS = 3
# Aperçus de liens : ces agents consommaient le jeton avant l'utilisateur
# (Telegram a brûlé un code en 2 s) et envoyaient la configuration à un tiers.
PREVIEW_BOTS = (
    "telegrambot", "whatsapp", "facebookexternalhit", "twitterbot", "slackbot",
    "discordbot", "linkedinbot", "googlebot", "bingbot", "embedly",
    "quora link preview", "pinterest", "skype", "vkshare", "outbrain",
)
MAX_APK_BYTES = 200 * 1024 * 1024

LOCK = threading.Lock()


# --- Codes d'invitation ------------------------------------------------------------
# Un parent connecté dépose une configuration (comme un code de configuration) ;
# elle est rendue UNE fois, pendant 1 h, contre un code de 8 chiffres. Essais
# limités pour rendre impossible de deviner un code.
TRACCAR_CHECK_URL = (os.environ.get("KW_TRACCAR_INTERNAL_URL", "") or TRACCAR_API_URL).rstrip("/")
INVITES = os.path.join(DATA_DIR, "invites.json")
INVITE_TTL_SECONDS = 3600
INVITE_LOCK = threading.Lock()
FAILURE_WINDOW = 600          # 10 min
FAILURES_PER_IP = 10
FAILURES_GLOBAL = 200
_failures = {}                # ip -> [horodatages]
_global_failures = []


def traccar_user(token):
    """Compte Traccar correspondant au jeton, ou None (jeton invalide, serveur injoignable)."""
    if not TRACCAR_CHECK_URL or not token:
        return None
    try:
        req = urllib.request.Request(
            f"{TRACCAR_CHECK_URL}/api/session?token={quote(token, safe='')}",
            headers={"Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.loads(response.read(65536))
    except Exception:
        return None


def client_ip(handler):
    """IP du client : dernière entrée de X-Forwarded-For (ajoutée par Traefik, non falsifiable)."""
    forwarded = handler.headers.get("X-Forwarded-For", "")
    if forwarded.strip():
        return forwarded.split(",")[-1].strip()
    return handler.client_address[0]


def too_many_failures(ip):
    cutoff = time.time() - FAILURE_WINDOW
    _failures[ip] = [t for t in _failures.get(ip, []) if t > cutoff]
    _global_failures[:] = [t for t in _global_failures if t > cutoff]
    return len(_failures[ip]) >= FAILURES_PER_IP or len(_global_failures) >= FAILURES_GLOBAL


def record_failure(ip):
    _failures.setdefault(ip, []).append(time.time())
    _global_failures.append(time.time())


def load_invites():
    try:
        with open(INVITES, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    limit = time.time()
    return {code: entry for code, entry in data.items() if entry.get("expires", 0) > limit}


def save_invites(data):
    save_json(INVITES, data)


def valid_invite_config(config, user):
    """Configuration acceptée pour une invitation (même format que les codes de configuration)."""
    if not isinstance(config, dict):
        return "configuration absente"
    mode = config.get("mode")
    if mode == "child":
        return None if valid_child(config.get("child")) else "configuration enfant invalide"
    if mode == "parent":
        if not user.get("administrator"):
            return "seul un compte administrateur peut inviter un parent"
        parent = config.get("parent")
        if not valid_parent(parent) or len(str((parent or {}).get("token", ""))) < 20:
            return "configuration parent invalide"
        return None
    return "mode doit valoir 'child' ou 'parent'"


def request_origin(handler):
    """« https://hôte » de la requête, tel que vu par le reverse proxy (Traefik)."""
    proto = (handler.headers.get("X-Forwarded-Proto") or "https").split(",")[0].strip() or "https"
    host = (handler.headers.get("X-Forwarded-Host") or handler.headers.get("Host") or "").split(",")[0].strip()
    return f"{proto}://{host}" if host else ""


def apk_base(handler):
    return APK_BASE_URL or request_origin(handler)


def config_base(handler):
    return BASE_URL or (request_origin(handler) + "/config")


def now():
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text):
    try:
        return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None


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
            text = f"{value:.0f} {unit}" if unit == "o" else f"{value:.1f} {unit}"
            return text.replace(".", ",")
        value /= 1024.0
    return f"{value:.1f} Go".replace(".", ",")


MONTHS = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."]

STYLE = """
*{box-sizing:border-box}
body{margin:0;min-height:100vh;padding:20px;display:flex;align-items:center;justify-content:center;
     background:radial-gradient(1200px 600px at 50% -10%,#182034 0%,#0b0d12 55%);color:#e8ebf2;
     font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
.card{width:100%;max-width:560px;background:rgba(20,24,33,.92);border:1px solid #222836;
      border-radius:24px;padding:28px 24px;box-shadow:0 24px 70px rgba(0,0,0,.5)}
.brand{display:inline-flex;align-items:center;gap:8px;font-size:11px;letter-spacing:.1em;
       text-transform:uppercase;color:#8b93a6;margin-bottom:14px}
.dot{width:8px;height:8px;border-radius:50%;background:linear-gradient(135deg,#6d8bff,#9d6bff)}
h1{margin:0 0 6px;font-size:22px;letter-spacing:-.01em}
.sub{margin:0 0 22px;color:#8b93a6;font-size:14px}
input{width:100%;padding:15px 18px;border-radius:16px;border:1px solid #2a3143;background:#0e1219;
      color:#fff;font-size:16px;outline:none;transition:border-color .15s,box-shadow .15s}
input:focus{border-color:#5b7cff;box-shadow:0 0 0 3px rgba(91,124,255,.16)}
.pill{display:inline-flex;align-items:center;justify-content:center;gap:8px;border:0;cursor:pointer;
      border-radius:999px;padding:14px 24px;font-size:15px;font-weight:600;color:#fff;text-decoration:none;
      background:linear-gradient(135deg,#5b7cff,#8a5cff);transition:transform .12s,filter .12s;
      font-family:inherit;white-space:nowrap}
.pill:hover{filter:brightness(1.09)} .pill:active{transform:scale(.98)}
.pill.full{width:100%;margin-top:12px}
.list{margin:0;padding:0;list-style:none}
.item{display:flex;align-items:center;gap:16px;padding:18px 0;border-top:1px solid #1f2532}
.item:first-child{border-top:0;padding-top:4px}
.ver{font-size:21px;font-weight:700;letter-spacing:-.02em;line-height:1.2}
.ver span{font-size:12px;font-weight:500;color:#7c8598;margin-left:8px;letter-spacing:0}
.file{font-size:13px;color:#9aa3b5;word-break:break-all;margin-top:3px}
.meta{font-size:12px;color:#6f7889;margin-top:3px}
.note{font-size:12px;color:#5f6779;margin-top:7px;font-style:italic}
.err{margin:14px 0 0;color:#ff7b8a;font-size:13px;min-height:18px}
.foot{margin-top:22px;padding-top:16px;border-top:1px solid #1f2532;display:flex;justify-content:space-between;
      font-size:12px;color:#5f6779}
.foot a{color:#6f7889;text-decoration:none} .foot a:hover{color:#9aa3b5}
.empty{text-align:center;color:#7c8598;padding:26px 0}
@media(max-width:520px){
  .card{padding:22px 18px;border-radius:20px}
  .item{flex-direction:column;align-items:stretch;gap:12px}
  .dl .pill{width:100%}
}
"""


def page(body, title="Dépôt d'APK"):
    return f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0b0d12">
<title>{html.escape(title)}</title>
<style>{STYLE}</style></head>
<body><div class="card">{body}</div></body></html>"""


def fr_when(iso_str):
    try:
        moment = datetime.strptime(iso_str, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        local = moment.astimezone()
        return f"{local.day} {MONTHS[local.month - 1]} {local.year} à {local:%H:%M}"
    except (TypeError, ValueError):
        return iso_str or ""


def split_version(raw):
    match = re.match(r"\s*([\w.\-+]+)\s*\((\d+)\)", raw or "")
    if match:
        return match.group(1), match.group(2)
    return (raw or "").strip(), ""


def latest_apk():
    """Dernier APK publié : le plus grand versionCode, à égalité le plus récent."""
    best = None
    for item in load_apks():  # déjà trié du plus récent au plus ancien
        name, code = split_version(item.get("version", ""))
        if not code.isdigit():
            continue
        if best is None or int(code) > best[0]:
            best = (int(code), name, item)
    return best


def render_login(error=""):
    return page(f"""<div class="brand"><span class="dot"></span>KinWaypoint</div>
<h1>Dépôt d'APK</h1>
<p class="sub">Entre le code d'accès pour voir les versions disponibles.</p>
<form method="post" action="/apk/login">
  <input name="code" type="password" inputmode="text" placeholder="Code d'accès"
         autofocus autocomplete="current-password" autocapitalize="off" spellcheck="false">
  <button class="pill full" type="submit">Entrer</button>
</form>
<p class="err">{html.escape(error)}</p>""")


def render_index(items):
    rows = []
    for it in items:
        name = it.get("file", "")
        version, build = split_version(it.get("version"))
        ver_html = f"v{html.escape(version)}" if version else "version inconnue"
        if build:
            ver_html += f"<span>· build {html.escape(build)}</span>"
        note = it.get("note") or ""
        note_html = f'<div class="note">{html.escape(note)}</div>' if note else ""
        rows.append(f"""<li class="item">
      <div style="flex:1;min-width:0">
        <div class="ver">{ver_html}</div>
        <div class="file">{html.escape(name)}</div>
        <div class="meta">{human(it.get("size", 0))} · {html.escape(fr_when(it.get("uploadedAt", "")))}</div>
        {note_html}
      </div>
      <div class="dl"><a class="pill" href="/apk/{html.escape(name)}">Télécharger</a></div>
    </li>""")
    body = rows and f'<ul class="list">{"".join(rows)}</ul>' or '<p class="empty">Aucun APK pour le moment.</p>'
    count = f"{len(items)} version" + ("s" if len(items) > 1 else "")
    return page(f"""<div class="brand"><span class="dot"></span>KinWaypoint</div>
<h1>Dépôt d'APK</h1>
<p class="sub">{count} — la plus récente en premier</p>
{body}
<div class="foot"><span></span><a href="/apk/logout">Se déconnecter</a></div>""")


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

    def reply_html(self, code, text, headers=None):
        body = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    # ------------------------------------------------------ accès par code
    def make_cookie(self):
        expires = int(time.time()) + COOKIE_DAYS * 86400
        signature = hmac.new(ADMIN_TOKEN.encode(), str(expires).encode(), hashlib.sha256).hexdigest()[:32]
        return f"{expires}.{signature}"

    def cookie_valid(self, value):
        try:
            expires, signature = value.split(".", 1)
            if int(expires) < time.time():
                return False
        except (ValueError, AttributeError):
            return False
        expected = hmac.new(ADMIN_TOKEN.encode(), expires.encode(), hashlib.sha256).hexdigest()[:32]
        return hmac.compare_digest(signature, expected)

    def apk_authorized(self):
        # 1) le code passé en query (pratique pour un script)
        query = parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
        if query.get("code") and hmac.compare_digest(query["code"][0], APK_CODE):
            return True
        # 2) le cookie déposé par la page de connexion
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            key, _, value = part.strip().partition("=")
            if key == COOKIE_NAME and self.cookie_valid(value):
                return True
        return False

    def require_apk_auth(self):
        if self.apk_authorized():
            return True
        self.reply_html(200, render_login(""))
        return False

    # -------------------------------------------------------------------- GET
    def do_GET(self):
        raw_path, _, query = self.path.partition("?")
        path = raw_path.rstrip("/")

        # URL canonique : sans le slash final, les liens relatifs d'un
        # navigateur repartent de la racine du domaine (404 sur l'APK).
        if raw_path == "/apk":
            suffix = f"?{query}" if query else ""
            self.reply_html(308, "", {"Location": f"/apk/{suffix}"})
            return

        if path == "/health":
            self.reply(200, {"status": "ok"})
            return

        if path == "/apk/logout":
            self.reply_html(303, "", {
                "Location": "/apk/",
                "Set-Cookie": f"{COOKIE_NAME}=; Path=/apk; Max-Age=0; HttpOnly; Secure; SameSite=Lax",
            })
            return

        # --- Découverte : adresses du serveur Traccar (PUBLIC, aucune donnée
        # sensible). Sans KW_TRACCAR_API_URL, on répond 404 : l'appli affiche
        # « Serveur familial introuvable » et ouvre les réglages avancés.
        if path == "/apk/server.json":
            if not TRACCAR_API_URL:
                self.reply(404, {"error": "KW_TRACCAR_API_URL non configuree"})
                return
            self.reply(200, {
                "version": 1,
                "apiUrl": TRACCAR_API_URL,
                "trackUrl": TRACCAR_TRACK_URL,
                "audioRelayUrl": AUDIO_RELAY_URL,
            })
            return

        # --- Mise à jour depuis l'appli : PUBLIC (sans code), et uniquement la
        # dernière version. La liste complète et les anciens APK restent protégés.
        if path == "/apk/latest.json":
            best = latest_apk()
            if best is None:
                self.reply(404, {"error": "aucun APK versionne"})
                return
            code, name, item = best
            self.reply(200, {
                "versionCode": code,
                "versionName": name,
                "file": item.get("file"),
                "size": item.get("size"),
                "sha256": item.get("sha256", ""),
                "uploadedAt": item.get("uploadedAt", ""),
                "note": item.get("note", ""),
                "apkUrl": f"{apk_base(self)}/apk/latest.apk",
            })
            return

        if path == "/apk/latest.apk":
            best = latest_apk()
            full = os.path.join(APK_DIR, best[2]["file"]) if best else ""
            if not best or not os.path.isfile(full):
                self.reply(404, {"error": "aucun APK"})
                return
            size = os.path.getsize(full)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{best[2]["file"]}"')
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with open(full, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 64 * 1024)
            self.log_message("apk (mise a jour) servi: %s (%d o)", best[2]["file"], size)
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
            user_agent = (self.headers.get("User-Agent") or "").lower()
            if any(bot in user_agent for bot in PREVIEW_BOTS):
                self.log_message("apercu de lien refuse (UA: %s)", user_agent[:50])
                self.reply(403, {"error": "apercu de lien refuse"})
                return
            if not token or "/" in token:
                self.reply(404, {"error": "inconnu"})
                return
            with LOCK:
                data = load()
                entry = data.get(token)
                if entry is None:
                    self.reply(404, {"error": "inconnu"})
                    return
                if expired(entry):
                    self.log_message("token expire")
                    self.reply(410, {"error": "expire"})
                    return
                if entry.get("used"):
                    used_at = parse_iso(entry.get("usedAt"))
                    reads = int(entry.get("usedCount", 1))
                    too_old = used_at is None or (now() - used_at) > timedelta(minutes=GRACE_MINUTES)
                    if too_old or reads >= MAX_READS:
                        self.log_message("token deja utilise (lectures: %d)", reads)
                        self.reply(410, {"error": "deja utilise"})
                        return
                    entry["usedCount"] = reads + 1
                    self.log_message("relecture toleree (%d/%d)", reads + 1, MAX_READS)
                else:
                    entry["used"] = True
                    entry["usedAt"] = iso(now())
                    entry["usedCount"] = 1
                    self.log_message("configuration servie (usage unique)")
                entry["usedFrom"] = self.client_address[0]
                data[token] = entry
                save(data)
                payload = dict(entry["payload"])
                payload["expiresAt"] = entry["expiresAt"]
            self.reply(200, payload)
            return

        self.reply(404, {"error": "route inconnue"})

    # ------------------------------------------------------------------- HEAD
    def do_HEAD(self):
        """Répond aux vérifications de lien (curl -I, gestionnaires de
        téléchargement Android) : mêmes en-têtes que GET, sans le corps."""
        raw_path, _, query = self.path.partition("?")
        path = raw_path.rstrip("/")

        def empty(code):
            self.send_response(code)
            self.send_header("Content-Length", "0")
            self.end_headers()

        if path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if path == "/apk/latest.json":
            self.send_response(200 if latest_apk() else 404)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if path == "/apk/server.json":
            self.send_response(200 if TRACCAR_API_URL else 404)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        target = ""
        if path == "/apk/latest.apk":
            best = latest_apk()
            target = os.path.join(APK_DIR, best[2]["file"]) if best else ""
            if not best or not os.path.isfile(target):
                empty(404)
                return
        elif path.startswith("/apk/"):
            if not self.apk_authorized():
                empty(401)
                return
            name = safe_name(path[len("/apk/"):])
            target = os.path.join(APK_DIR, name)
            if not name or not os.path.isfile(target):
                empty(404)
                return
        else:
            empty(404)
            return

        size = os.path.getsize(target)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(target)}"')
        self.end_headers()

    # ------------------------------------------------------------------- POST
    def read_json(self, limit=16384):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length > limit:
            raise ValueError("corps trop volumineux")
        return json.loads(self.rfile.read(length) or b"{}")

    def create_invite(self):
        """POST /apk/invite (Authorization: Bearer <jeton Traccar>) : {config} -> {code, expiresAt}."""
        auth = self.headers.get("Authorization", "")
        user = traccar_user(auth[7:].strip() if auth.startswith("Bearer ") else "")
        if not user:
            self.reply(401, {"error": "connexion Traccar requise"})
            return
        try:
            body = self.read_json()
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"error": "corps JSON invalide"})
            return
        config = body.get("config")
        problem = valid_invite_config(config, user)
        if problem:
            self.reply(403 if "administrateur" in problem else 400, {"error": problem})
            return
        expires = time.time() + INVITE_TTL_SECONDS
        with INVITE_LOCK:
            invites = load_invites()
            code = f"{secrets.randbelow(10 ** 8):08d}"
            while code in invites:
                code = f"{secrets.randbelow(10 ** 8):08d}"
            invites[code] = {"config": dict(config, version=1), "expires": expires, "by": user.get("id")}
            save_invites(invites)
        self.log_message(f"invitation creee mode={config.get('mode')} par compte {user.get('id')}")
        self.reply(201, {"code": code, "expiresAt": iso(datetime.fromtimestamp(expires, timezone.utc))})

    def redeem_invite(self):
        """POST /apk/invite/redeem : {code} -> configuration, une seule fois."""
        ip = client_ip(self)
        with INVITE_LOCK:
            if too_many_failures(ip):
                self.reply(429, {"error": "trop d'essais, reessaie dans quelques minutes"})
                return
            try:
                code = re.sub(r"\D", "", str(self.read_json(1024).get("code", "")))
            except (ValueError, json.JSONDecodeError, AttributeError):
                code = ""
            invites = load_invites()
            entry = invites.pop(code, None) if len(code) == 8 else None
            if entry is None:
                record_failure(ip)
                self.reply(404, {"error": "code invalide, expire ou deja utilise"})
                return
            save_invites(invites)
        self.log_message(f"invitation utilisee mode={entry['config'].get('mode')}")
        self.reply(200, entry["config"])

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/apk/login":
            self.login()
            return

        if path == "/apk/upload":
            if not ADMIN_TOKEN or self.headers.get("X-Admin-Token") != ADMIN_TOKEN:
                self.log_message("refus depot apk (jeton admin)")
                self.reply(403, {"error": "interdit"})
                return
            self.upload_apk()
            return

        if path == "/apk/invite":
            self.create_invite()
            return

        if path == "/apk/invite/redeem":
            self.redeem_invite()
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
                "intervalSeconds": int(child.get("intervalSeconds", 300)),
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

        url = f"{config_base(self)}/kw/{token}"
        self.log_message(f"code cree mode={mode} ttl={ttl}h")
        self.reply(201, {"url": url, "token": token, "expiresAt": iso(expires)})

    def login(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            form = parse_qs(self.rfile.read(length).decode("utf-8", "replace"))
        except ValueError:
            form = {}
        code = (form.get("code") or [""])[0].strip()
        if not code or not hmac.compare_digest(code, APK_CODE):
            self.log_message("code d'acces refuse")
            self.reply_html(200, render_login("Code incorrect, réessaie."))
            return
        self.log_message("depot apk deverrouille")
        self.reply_html(303, "", {
            "Location": "/apk/",
            "Set-Cookie": f"{COOKIE_NAME}={self.make_cookie()}; Path=/apk; "
                          f"Max-Age={COOKIE_DAYS * 86400}; HttpOnly; Secure; SameSite=Lax",
        })

    def header_utf8(self, name, default=""):
        """Les en-têtes HTTP sont décodés en latin-1 : on récupère l'UTF-8 réel.

        Sans ça, un « · » envoyé par curl arrive sous forme de « Â· ».
        """
        raw = self.headers.get(name, default) or default
        try:
            return raw.encode("latin-1").decode("utf-8")
        except (UnicodeDecodeError, UnicodeEncodeError):
            return raw

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
            "project": self.header_utf8("X-Project", "KinWaypoint")[:40],
            "version": self.header_utf8("X-Version")[:40],
            "size": length,
            "uploadedAt": iso(now()),
            "sha256": digest.hexdigest(),
            "note": self.header_utf8("X-Note")[:200],
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
        self.reply(201, {"ok": True, "file": name, "url": f"{apk_base(self)}/apk/{name}", "sha256": entry["sha256"]})


if __name__ == "__main__":
    print(f"KinWaypoint sur :{PORT} | base={BASE_URL or '(déduite des requêtes)'} | data={DATA_DIR}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
