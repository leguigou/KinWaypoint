"""
Relais audio KinWaypoint : « écoute en direct » visible.

Le téléphone du parent ouvre d'abord une salle (role=sub) avec un secret à usage
unique, puis demande à Traccar d'envoyer la commande au téléphone de l'enfant.
Le téléphone de l'enfant rejoint la salle (role=pub) et y diffuse son micro
(PCM 16 bits, 16 kHz, mono). Le relais se contente de transmettre les trames :
rien n'est enregistré, rien n'est écrit sur disque.

Sécurité :
- seul un parent (role=sub) peut créer une salle ; l'enfant ne fait que la rejoindre ;
- le secret est comparé en temps constant ; 20 salles au maximum ;
- une salle sans enfant au bout de 2 min, ou plus vieille que la durée maximale
  (10 min par défaut), est fermée ; quand le parent raccroche, l'enfant est coupé.
"""

import asyncio
import hmac
import logging
import os
import re
import time
from http import HTTPStatus
from urllib.parse import parse_qs, urlparse

from websockets.asyncio.server import broadcast, serve
from websockets.exceptions import ConnectionClosed

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("audio-relay")

PORT = int(os.environ.get("KW_AUDIO_PORT", "8765"))
MAX_SESSION = int(os.environ.get("KW_AUDIO_MAX_SECONDS", "600"))
WAIT_PUB = 120
MAX_ROOMS = 20
MAX_SUBS = 3
ROOM_RE = re.compile(r"^[A-Za-z0-9]{16,64}$")

# salle -> {"secret", "pub", "subs", "created"}
rooms = {}


def close_room(room, code=1000, reason="fin"):
    r = rooms.pop(room, None)
    if r is None:
        return
    peers = list(r["subs"]) + ([r["pub"]] if r["pub"] is not None else [])
    for ws in peers:
        asyncio.ensure_future(ws.close(code, reason))
    log.info("salle %s… fermée (%s)", room[:6], reason)


async def handler(ws):
    url = urlparse(ws.request.path)
    room = url.path.rstrip("/").rsplit("/", 1)[-1]
    query = parse_qs(url.query)
    role = query.get("role", [""])[0]
    secret = query.get("secret", [""])[0]

    if not ROOM_RE.match(room) or len(secret) < 16 or role not in ("pub", "sub"):
        await ws.close(4400, "requête invalide")
        return

    r = rooms.get(room)
    if r is None:
        # Seul le parent crée une salle : un téléphone d'enfant ne peut pas en ouvrir.
        if role != "sub":
            await ws.close(4404, "salle inconnue")
            return
        if len(rooms) >= MAX_ROOMS:
            await ws.close(4429, "trop de salles")
            return
        r = rooms[room] = {"secret": secret, "pub": None, "subs": set(), "created": time.monotonic()}
        log.info("salle %s… ouverte", room[:6])
    elif not hmac.compare_digest(r["secret"], secret):
        await ws.close(4403, "secret invalide")
        return

    if role == "pub":
        if r["pub"] is not None:
            await ws.close(4409, "déjà diffusé")
            return
        r["pub"] = ws
        broadcast(r["subs"], "pub-joined")
        log.info("salle %s… : micro connecté", room[:6])
        try:
            async for message in ws:
                if isinstance(message, bytes):
                    broadcast(r["subs"], message)
        except ConnectionClosed:
            pass
        finally:
            if rooms.get(room) is r:
                r["pub"] = None
                close_room(room, 1000, "micro déconnecté")
    else:
        if len(r["subs"]) >= MAX_SUBS:
            await ws.close(4429, "trop d'auditeurs")
            return
        r["subs"].add(ws)
        try:
            async for _ in ws:
                pass  # le parent n'envoie rien
        except ConnectionClosed:
            pass
        finally:
            r["subs"].discard(ws)
            if not r["subs"] and rooms.get(room) is r:
                close_room(room, 4000, "parent parti")


async def janitor():
    while True:
        await asyncio.sleep(10)
        now = time.monotonic()
        for room, r in list(rooms.items()):
            age = now - r["created"]
            if r["pub"] is None and age > WAIT_PUB:
                close_room(room, 4408, "le téléphone n'a pas répondu")
            elif age > MAX_SESSION:
                close_room(room, 4001, "durée maximale atteinte")


def health(connection, request):
    if request.path.rstrip("/").endswith("/health"):
        return connection.respond(HTTPStatus.OK, "OK\n")
    return None


async def main():
    async with serve(handler, "0.0.0.0", PORT, max_size=2 ** 16, ping_interval=20, process_request=health):
        log.info("relais audio prêt sur le port %d", PORT)
        await janitor()


if __name__ == "__main__":
    asyncio.run(main())
