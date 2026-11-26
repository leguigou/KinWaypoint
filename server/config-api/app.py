#!/usr/bin/env python3
"""KinWaypoint — serveur de codes de configuration.

Sert le contrat décrit dans docs/CONFIG-DISTANTE.md :

    GET  /kw/<token>   → 200 JSON de configuration (usage unique), 410 si déjà
                         utilisé, 404 si inconnu, 410 si expiré
    POST /api/codes    → création d'un code (admin), renvoie l'URL à coller
    GET  /health       → {"status":"ok"}

Le jeton fait 32 octets aléatoires (256 bits). Il n'est jamais réutilisable :
le premier appel réussi le consomme, conformément à la doc.

Aucune dépendance externe : uniquement la bibliothèque standard.
"""
import json
import os
import secrets
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA_DIR = os.environ.get("KW_DATA_DIR", "/data")
PORT = int(os.environ.get("KW_PORT", "8080"))
BASE_URL = os.environ.get("KW_BASE_URL", "https://config.deloffre.fr").rstrip("/")
ADMIN_TOKEN = os.environ.get("KW_ADMIN_TOKEN", "")
DEFAULT_TTL_HOURS = int(os.environ.get("KW_TTL_HOURS", "48"))
STORE = os.path.join(DATA_DIR, "codes.json")

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


class Handler(BaseHTTPRequestHandler):
    server_version = "KinWaypointConfig/1.0"

    def log_message(self, format, *args):
        print(f"{iso(now())} {self.client_address[0]} {format % args}", flush=True)

    def reply(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/health":
            self.reply(200, {"status": "ok"})
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

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
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


if __name__ == "__main__":
    print(f"KinWaypoint config sur :{PORT} | base={BASE_URL} | store={STORE}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
