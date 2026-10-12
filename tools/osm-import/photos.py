"""Foto vere dei rifugi da Wikimedia Commons, caricate con il flusso normale dell'API (upload-request, storage, confirm).

Uso, dalla root del backend con lo stack acceso, dopo import_calabria.sh:
    python tools/osm-import/photos.py            # prova: elenca le foto che sceglierebbe
    python tools/osm-import/photos.py --upload   # le carica come manager@geosentinel.dev (gestore dei rifugi)
    python tools/osm-import/photos.py --stock    # poi, ai rifugi rimasti senza: una foto illustrativa (vedi STOCK_*)

Solo licenze libere (CC BY, CC BY-SA, CC0, pubblico dominio). Una foto si sceglie se:
- è collegata al rifugio su OpenStreetMap (wikimedia_commons, image, wikidata -> P18), oppure
- è geolocalizzata su Commons entro 200 m dal rifugio e il titolo ne contiene il nome (o "rifugio"/"bivacco").
Meglio un rifugio senza foto che con la foto di un altro posto. Autore e licenza vanno sulla foto (credit).
"""
import json
import re
import subprocess
import time
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).parent
API = "http://localhost:8080/api/v1"
COMMONS = "https://commons.wikimedia.org/w/api.php"
WIKIDATA = "https://www.wikidata.org/wiki/Special:EntityData/{}.json"
UA = "GeoSentinel-local-import/0.1 (progetto universitario; foto dei rifugi da Wikimedia Commons)"
MANAGER = ("manager@geosentinel.dev", "Password123!")
FREE = re.compile(r"^(CC BY(-SA)?( \d\.\d)?|CC0|Public domain|PD.*)$", re.I)
# parole troppo comuni per dire che una foto è proprio di quel rifugio
COMMON_WORDS = {"rifugio", "bivacco", "del", "della", "dei", "di", "da", "il", "lo", "la", "le", "monte", "comunale",
                "casello", "baita", "piano", "chiuso", "2026", "s.l.m."}
WIDTH = 1600
# Foto scelte dalla ricerca ma scartate guardandole (12/10/2026): non mostrano il rifugio
REJECTED = {
    "File:Cavaliere di Lorica 2.jpg",  # un parcheggio, il rifugio appena visibile sullo sfondo
    "File:Fontanella in pietra nei pressi della casetta dei Marziali a Ferdinandea.jpg",  # la fontana vicina
}


def get_json(url, params=None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    # Wikimedia vuole richieste in serie e lente: una al secondo, e con un 429 si aspetta quanto dice Retry-After
    for attempt in range(6):
        time.sleep(1)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.load(r)
            # la ricerca di Commons a volte risponde 200 con un errore ("too busy"): si ritenta come un 429
            error = data.get("error", {}).get("code", "") if isinstance(data, dict) else ""
            if error and attempt < 5 and ("busy" in error or "ratelimit" in error or error == "maxlag"):
                time.sleep(15)
                continue
            return data
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 5:
                raise
            time.sleep(int(e.headers.get("Retry-After") or 10))


def commons(params):
    return get_json(COMMONS, {**params, "format": "json", "formatversion": "2"})


def file_title(value):
    """Il titolo 'File:...' da un tag OSM (titolo o URL di Commons)."""
    if not value:
        return None
    value = urllib.parse.unquote(value.split("/wiki/")[-1]).replace("_", " ")
    return value if value.startswith("File:") else None


def category_files(category):
    data = commons({"action": "query", "list": "categorymembers", "cmtitle": category, "cmtype": "file", "cmlimit": 5})
    return [m["title"] for m in data["query"]["categorymembers"]]


def wikidata_image(qid):
    claims = get_json(WIKIDATA.format(qid))["entities"][qid]["claims"]
    p18 = claims.get("P18")
    return "File:" + p18[0]["mainsnak"]["datavalue"]["value"] if p18 else None


def nearby_titles(refuge):
    data = commons({"action": "query", "list": "geosearch", "gscoord": f"{refuge['lat']}|{refuge['lon']}",
                    "gsradius": 200, "gsnamespace": 6, "gslimit": 50})
    words = {w for w in re.findall(r"[\wàèéìòù.]+", refuge["name"].lower()) if w not in COMMON_WORDS and len(w) > 2}
    keep = []
    for hit in data["query"]["geosearch"]:
        title = hit["title"].lower()
        if any(w in title for w in words) or "rifugio" in title or "bivacco" in title:
            keep.append(hit["title"])
    return keep


def file_info(title):
    data = commons({"action": "query", "titles": title, "prop": "imageinfo",
                    "iiprop": "url|mime|extmetadata", "iiurlwidth": WIDTH})
    page = data["query"]["pages"][0]
    if "imageinfo" not in page:
        return None
    info = page["imageinfo"][0]
    meta = info.get("extmetadata", {})
    license_name = meta.get("LicenseShortName", {}).get("value", "")
    if info["mime"] not in ("image/jpeg", "image/png") or not FREE.match(license_name.strip()):
        return None
    artist = re.sub(r"<[^>]+>", "", meta.get("Artist", {}).get("value", "")).strip() or "autore sconosciuto"
    return {"title": title, "url": info.get("thumburl") or info["url"], "mime": info["mime"],
            "page": info["descriptionurl"], "artist": artist[:120], "license": license_name.strip()}


def choose(refuge):
    candidates = []
    commons_tag = refuge.get("commons") or ""
    if commons_tag.startswith("Category:"):
        candidates += category_files(commons_tag)
    candidates.append(file_title(commons_tag))
    candidates.append(file_title(refuge.get("image")))
    if refuge.get("wikidata"):
        candidates.append(wikidata_image(refuge["wikidata"]))
    candidates += nearby_titles(refuge)
    for title in dict.fromkeys(c for c in candidates if c and c not in REJECTED):
        info = file_info(title)
        if info:
            return info
    return None


# ---- caricamento con l'API, come l'app ----

def api(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def download(photo):
    req = urllib.request.Request(photo["url"], headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def upload(token, refuge_id, photo, content=None):
    content = content or download(photo)
    ext = "jpg" if photo["mime"] == "image/jpeg" else "png"
    request = api("POST", "/media/upload-requests", {
        "originalFilename": f"rifugio.{ext}", "contentType": photo["mime"], "sizeBytes": len(content),
        "entityType": "REFUGE", "entityId": refuge_id}, token)
    # multipart: prima i campi firmati, poi il file (ultimo, stesso Content-Type), con Content-Length
    boundary = uuid.uuid4().hex
    parts = b"".join(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
        for k, v in request["formFields"].items())
    parts += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="rifugio.{ext}"\r\n'
              f'Content-Type: {photo["mime"]}\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
    storage = urllib.request.Request(request["uploadUrl"], data=parts, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    urllib.request.urlopen(storage, timeout=120).close()
    return request["mediaId"], api("POST", f"/media/{request['mediaId']}/confirm", None, token)["status"]


def credit(media_id, photo, label="Foto"):
    """Autore, licenza e fonte sulla foto (media_files.credit, V12): l'app li mostra dietro la "i" della foto."""
    title = urllib.parse.unquote(photo["page"].split("/wiki/")[-1]).replace("_", " ")
    line = f"{label}. {photo['artist']}, {photo['license']}, Wikimedia Commons ({title})"[:300]
    sql = "UPDATE media_files SET credit = :'line' WHERE id = :'id';"
    subprocess.run(["docker", "exec", "-i", "geosentinel-postgres", "psql", "-q", "-v", "ON_ERROR_STOP=1",
                    "-U", "postgres", "-d", "geosentinel_db", "-v", f"line={line}", "-v", f"id={media_id}"],
                   input=sql, text=True, check=True)


# ---- foto illustrative ----
# Per i rifugi senza una foto vera (la maggior parte: su Commons ce ne sono poche) una foto di un altro rifugio
# appenninico, scelta guardandola (12/10/2026): niente nomi leggibili, così non si scambia per quel posto. Il
# credito dice che è illustrativa. Bivacchi (casette di pietra isolate) e rifugi (edifici più grandi) a parte.
STOCK_BIVOUACS = [
    "File:Serra Lunga (AQ) Rifugio Coppo dell orso 01.jpg",
    "File:Serra Lunga (AQ) Rifugio Coppo dell orso 04.jpg",
    "File:CAI 181 Bivacco fisso Pian dell'Orzo.jpg",
    "File:Poggio di Petto neve.jpg",
]
STOCK_HUTS = [
    "File:Rif. franchetti (DC) - panoramio.jpg",
    "File:Rifugio Carlo Franchetti 01.jpg",
    "File:Rifugio Scaffaiolo 1.jpg",
    "File:Rifugio Uso Di Sotto - panoramio.jpg",
    'File:Restaurant "Il Cavone" 1.jpg',
    'File:Restaurant "Il Cavone" 3.jpg',
    "File:Pescia, rifugio Uso di Sotto, retro.jpg",
    "File:Rifugio Rossi.jpg",
    "File:Rifugio Le terrazze.jpg",
    "File:Rifugio Scaffaiolo 2.jpg",
    "File:Strofinatoio 09.jpg",
]


def stock():
    rows = subprocess.run(
        ["docker", "exec", "geosentinel-postgres", "psql", "-At", "-F", "|", "-U", "postgres", "-d", "geosentinel_db",
         "-c", "SELECT r.id, r.name, coalesce(r.description, '') LIKE '%Bivacco incustodito%' FROM refuges r "
               "WHERE NOT EXISTS (SELECT 1 FROM media_files m WHERE m.entity_type = 'REFUGE' AND m.entity_id = r.id) "
               "ORDER BY r.name"],
        capture_output=True, text=True, check=True).stdout.splitlines()
    token = api("POST", "/auth/login", {"email": MANAGER[0], "password": MANAGER[1]})["accessToken"]
    cache = {}
    for row in rows:
        refuge_id, name, bivouac = row.split("|")
        pool = STOCK_BIVOUACS if bivouac == "t" else STOCK_HUTS
        # sempre la stessa per lo stesso rifugio, e ben distribuite
        title = pool[int(refuge_id.replace("-", ""), 16) % len(pool)]
        if title not in cache:
            info = file_info(title)
            cache[title] = (info, download(info))
        info, content = cache[title]
        media_id, status = upload(token, refuge_id, info, content)
        credit(media_id, info, "Foto illustrativa, non di questo rifugio")
        print(f"{name} <- {title}: {status}")
    print(f"{len(rows)} rifugi con una foto illustrativa")


def main():
    if "--stock" in sys.argv:
        return stock()
    do_upload = "--upload" in sys.argv
    refuges = json.loads((HERE / ".cache" / "refuges.json").read_text(encoding="utf-8"))
    token = api("POST", "/auth/login", {"email": MANAGER[0], "password": MANAGER[1]})["accessToken"] if do_upload else None
    # i rifugi che hanno già una foto si saltano: rilanciare lo script non crea doppioni
    done = set(subprocess.run(
        ["docker", "exec", "geosentinel-postgres", "psql", "-At", "-U", "postgres", "-d", "geosentinel_db", "-c",
         "SELECT DISTINCT entity_id FROM media_files WHERE entity_type = 'REFUGE'"],
        capture_output=True, text=True, check=True).stdout.split())
    refuges = [r for r in refuges if r["id"] not in done]
    found = 0
    for refuge in refuges:
        try:
            photo = choose(refuge)
        except Exception as e:  # una ricerca fallita non ferma le altre
            print(f"! {refuge['name']}: {e}")
            continue
        if not photo:
            continue
        found += 1
        print(f"{refuge['name']} <- {photo['title']} ({photo['license']}, {photo['artist']})")
        if do_upload:
            media_id, status = upload(token, refuge["id"], photo)
            credit(media_id, photo)
            print(f"   {status}")
    print(f"{found} rifugi su {len(refuges)} con una foto")


if __name__ == "__main__":
    main()
