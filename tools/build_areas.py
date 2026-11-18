"""Gera as áreas (polígonos) dos trechos de praia e das zonas de risco.

Lê data/cprh_points.json, baixa a linha de costa do OpenStreetMap (natural=coastline)
via Overpass e, para cada ponto de coleta:
  1. encaixa o ponto na linha de costa mais próxima;
  2. pega o trecho de costa ao redor (até MAX_HALF_LENGTH_M para cada lado, sem
     invadir o trecho do ponto vizinho);
  3. desloca esse trecho para gerar uma faixa que vai da areia até o mar.

Saídas:
  data/areas.geojson                                   (para inspeção, ex.: geojson.io)
  supabase/seed.sql                                    (praias + zonas de risco)
  shared/src/commonMain/kotlin/com/tuba/data/fake/SeedData.kt  (modo demonstração)

Uso: python tools/build_areas.py [--refresh]   (--refresh baixa a costa de novo)
Somente biblioteca padrão.
"""

import csv
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POINTS_FILE = ROOT / "data" / "cprh_points.json"
BULLETINS_DIR = ROOT / "data" / "boletins"
CACHE_FILE = ROOT / "tools" / ".cache" / "coastline.json"
GEOJSON_OUT = ROOT / "data" / "areas.geojson"
SEED_OUT = ROOT / "supabase" / "seed.sql"
KOTLIN_OUT = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "tuba" / "data" / "fake" / "SeedData.kt"

BBOX = (-9.0, -35.3, -7.6, -34.7)  # sul, oeste, norte, leste
OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
USER_AGENT = "tuba-app-dev/0.1 (mapa de balneabilidade de PE)"

MAX_HALF_LENGTH_M = 900  # extensão máxima do trecho para cada lado do ponto
NEIGHBOR_GAP_M = 20  # folga entre trechos vizinhos
LAND_WIDTH_M = 35  # faixa de areia
SEA_WIDTH_M = 220  # faixa de mar (área de banho)
SNAP_WARN_M = 1500  # avisa se o ponto estiver longe da costa
MIN_CHAIN_M = 2000  # ignora ilhotas e molhes no encaixe dos pontos

# Projeção equiretangular local em metros (suficiente para o litoral de PE).
LAT0 = -8.3
KX = 111_320 * math.cos(math.radians(LAT0))
KY = 110_574


def proj(lat, lng):
    return (lng * KX, lat * KY)


def unproj(x, y):
    return (y / KY, x / KX)


def fetch_coastline(refresh):
    if CACHE_FILE.exists() and not refresh:
        return json.loads(CACHE_FILE.read_text(encoding="utf-8"))
    s, w, n, e = BBOX
    query = f'[out:json][timeout:120];way["natural"="coastline"]({s},{w},{n},{e});out geom;'
    body = urllib.parse.urlencode({"data": query}).encode()
    last_error = None
    for url in OVERPASS_URLS:
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, data=body, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=180) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
                CACHE_FILE.write_text(json.dumps(data), encoding="utf-8")
                return data
            except Exception as exc:  # noqa: BLE001 - tenta o próximo servidor
                last_error = exc
                time.sleep(5 * (attempt + 1))
    raise SystemExit(f"Não foi possível baixar a linha de costa: {last_error}")


def build_chains(overpass):
    """Junta os ways de costa em linhas contínuas (o OSM orienta a costa com terra à esquerda)."""
    ways = []
    for el in overpass["elements"]:
        if el.get("type") == "way" and el.get("geometry"):
            ways.append([proj(p["lat"], p["lon"]) for p in el["geometry"]])

    def key(p):
        return (round(p[0], 2), round(p[1], 2))

    by_start = {}
    for i, w in enumerate(ways):
        by_start.setdefault(key(w[0]), []).append(i)
    used = set()
    chains = []
    for i in range(len(ways)):
        if i in used:
            continue
        used.add(i)
        chain = list(ways[i])
        # estende para frente
        while True:
            nxt = [j for j in by_start.get(key(chain[-1]), []) if j not in used]
            if not nxt:
                break
            used.add(nxt[0])
            chain.extend(ways[nxt[0]][1:])
        # estende para trás
        while True:
            prev = [j for j, w in enumerate(ways) if j not in used and key(w[-1]) == key(chain[0])]
            if not prev:
                break
            used.add(prev[0])
            chain = ways[prev[0]][:-1] + chain
        chains.append(chain)
    return chains


def cumulative(chain):
    acc = [0.0]
    for a, b in zip(chain, chain[1:]):
        acc.append(acc[-1] + math.dist(a, b))
    return acc


def snap(chains, cums, p):
    best = None
    for ci, chain in enumerate(chains):
        cum = cums[ci]
        for si in range(len(chain) - 1):
            ax, ay = chain[si]
            bx, by = chain[si + 1]
            dx, dy = bx - ax, by - ay
            seg2 = dx * dx + dy * dy
            t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / seg2))
            q = (ax + t * dx, ay + t * dy)
            d = math.dist(p, q)
            if best is None or d < best[0]:
                best = (d, ci, cum[si] + t * math.sqrt(seg2), q)
    return best


def point_at(chain, cum, s):
    s = max(0.0, min(cum[-1], s))
    for i in range(len(chain) - 1):
        if cum[i + 1] >= s:
            seg = cum[i + 1] - cum[i]
            t = 0.0 if seg == 0 else (s - cum[i]) / seg
            a, b = chain[i], chain[i + 1]
            return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
    return chain[-1]


def extract(chain, cum, s0, s1):
    pts = [point_at(chain, cum, s0)]
    pts += [chain[i] for i in range(len(chain)) if s0 < cum[i] < s1]
    pts.append(point_at(chain, cum, s1))
    return pts


def densify(pts, step):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        n = max(1, int(math.dist(a, b) // step))
        for k in range(1, n + 1):
            out.append((a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n))
    return out


def smooth(pts, window):
    if len(pts) < 3:
        return pts
    out = []
    for i in range(len(pts)):
        lo, hi = max(0, i - window), min(len(pts), i + window + 1)
        xs = [p[0] for p in pts[lo:hi]]
        ys = [p[1] for p in pts[lo:hi]]
        out.append((sum(xs) / len(xs), sum(ys) / len(ys)))
    out[0], out[-1] = pts[0], pts[-1]
    return out


def right_normals(pts):
    """Normal à direita da direção da linha: no OSM, é o lado do mar."""
    normals = []
    for i in range(len(pts)):
        a = pts[max(0, i - 1)]
        b = pts[min(len(pts) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy) or 1.0
        normals.append((dy / length, -dx / length))
    return smooth(normals, 4)


def band(line, land, sea):
    line = smooth(densify(line, 25), 2)
    normals = right_normals(line)
    inner = [(p[0] - n[0] * land, p[1] - n[1] * land) for p, n in zip(line, normals)]
    outer = [(p[0] + n[0] * sea, p[1] + n[1] * sea) for p, n in zip(line, normals)]
    ring = inner + outer[::-1]
    ring.append(ring[0])
    return [unproj(x, y) for x, y in ring]


def shark_band(chains, zone):
    """Faixa de mar ao longo da costa leste: pega o ponto de costa mais a leste por latitude,
    o que ignora estuários e o porto do Recife."""
    lat_n, lat_s = zone["from"]["lat"], zone["to"]["lat"]
    _, y_n = proj(lat_n, 0)
    _, y_s = proj(lat_s, 0)
    bins = {}
    step = 60
    for chain in chains:
        for x, y in densify(chain, 40):
            if y_s <= y <= y_n and x > proj(0, -35.0)[0]:
                b = int((y - y_s) // step)
                bins[b] = max(bins.get(b, -1e18), x)
    keys = sorted(bins)
    # Mediana móvel remove picos de molhes e quebra-mares (ex.: porto do Recife).
    # Só troca pela mediana onde há pico real; nas curvas a mediana puxaria a borda para a terra.
    raw = [bins[k] for k in keys]
    medians = [sorted(raw[max(0, i - 15):i + 16])[len(raw[max(0, i - 15):i + 16]) // 2] for i in range(len(raw))]
    xs = [m if r - m > 300 else r for r, m in zip(raw, medians)]
    shore = smooth([(x, y_s + (k + 0.5) * step) for x, k in zip(xs, keys)], 1)
    # A suavização não pode empurrar a borda para dentro da terra.
    shore = [(max(x, xr), y) for (x, y), xr in zip(shore, xs)]
    shore = shore[::-1]  # norte -> sul
    width = zone["sea_width_m"]
    ring = [(x, y) for x, y in shore] + [(x + width, y) for x, y in shore[::-1]]
    ring.append(ring[0])
    return [unproj(x, y) for x, y in ring]


def latest_bulletin():
    files = sorted(BULLETINS_DIR.glob("*.csv"))
    if not files:
        return None, {}
    path = files[-1]
    rows = csv.DictReader(line for line in path.read_text(encoding="utf-8").splitlines() if not line.startswith("#"))
    return path.stem, {r["point_id"]: r["status"] for r in rows}


def ring_to_text(ring):
    return ";".join(f"{lat:.5f},{lng:.5f}" for lat, lng in ring)


def sql_str(s):
    return "'" + s.replace("'", "''") + "'"


def main():
    refresh = "--refresh" in sys.argv
    spec = json.loads(POINTS_FILE.read_text(encoding="utf-8"))
    chains = [c for c in build_chains(fetch_coastline(refresh)) if len(c) > 1]
    chains = [c for c in chains if cumulative(c)[-1] >= MIN_CHAIN_M]
    cums = [cumulative(c) for c in chains]
    print(f"{len(chains)} linhas de costa")

    snapped = []
    for p in spec["points"]:
        d, ci, s, q = snap(chains, cums, proj(p["lat"], p["lng"]))
        if d > SNAP_WARN_M:
            print(f"AVISO: {p['id']} está a {d:.0f} m da costa; confira as coordenadas")
        snapped.append((p, ci, s, q))

    beaches = []
    for p, ci, s, q in snapped:
        same = sorted(o[2] for o in snapped if o[1] == ci and o[0] is not p)
        before = [x for x in same if x < s]
        after = [x for x in same if x > s]
        half_before = min(MAX_HALF_LENGTH_M, (s - before[-1]) / 2 - NEIGHBOR_GAP_M) if before else MAX_HALF_LENGTH_M
        half_after = min(MAX_HALF_LENGTH_M, (after[0] - s) / 2 - NEIGHBOR_GAP_M) if after else MAX_HALF_LENGTH_M
        line = extract(chains[ci], cums[ci], s - half_before, s + half_after)
        ring = band(line, LAND_WIDTH_M, SEA_WIDTH_M)
        lat, lng = unproj(*q)
        beaches.append({**p, "lat": round(lat, 5), "lng": round(lng, 5), "ring": ring})
        print(f"{p['id']:34s} trecho {half_before + half_after:5.0f} m")

    zones = [{**z, "ring": shark_band(chains, z)} for z in spec["risk_zones"]]
    bulletin_date, statuses = latest_bulletin()

    # GeoJSON para inspeção
    features = [
        {
            "type": "Feature",
            "properties": {"id": b["id"], "name": b["name"], "city": b["city"], "status": statuses.get(b["id"])},
            "geometry": {"type": "Polygon", "coordinates": [[[lng, lat] for lat, lng in b["ring"]]]},
        }
        for b in beaches
    ] + [
        {
            "type": "Feature",
            "properties": {"id": z["id"], "kind": z["kind"], "name": z["name"]},
            "geometry": {"type": "Polygon", "coordinates": [[[lng, lat] for lat, lng in z["ring"]]]},
        }
        for z in zones
    ]
    GEOJSON_OUT.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")

    # Seed SQL
    lines = [
        "-- Gerado por tools/build_areas.py. Não edite à mão: altere data/cprh_points.json.",
        "-- Áreas derivadas da linha de costa do OpenStreetMap (© colaboradores do OSM, ODbL).",
        "",
        "insert into beaches (id, name, city, reference, lat, lng, area) values",
    ]
    rows = []
    for b in beaches:
        geom = {"type": "Polygon", "coordinates": [[[round(lng, 5), round(lat, 5)] for lat, lng in b["ring"]]]}
        rows.append(
            f"  ({sql_str(b['id'])}, {sql_str(b['name'])}, {sql_str(b['city'])}, {sql_str(b['reference'])}, "
            f"{b['lat']}, {b['lng']}, {sql_str(json.dumps(geom, separators=(',', ':')))}::jsonb)"
        )
    lines.append(",\n".join(rows))
    lines.append(
        "on conflict (id) do update set name = excluded.name, city = excluded.city, "
        "reference = excluded.reference, lat = excluded.lat, lng = excluded.lng, area = excluded.area;"
    )
    lines.append("")
    lines.append("insert into risk_zones (id, kind, name, description, source, area) values")
    zrows = []
    for z in zones:
        geom = {"type": "Polygon", "coordinates": [[[round(lng, 5), round(lat, 5)] for lat, lng in z["ring"]]]}
        zrows.append(
            f"  ({sql_str(z['id'])}, {sql_str(z['kind'])}, {sql_str(z['name'])}, {sql_str(z['description'])}, "
            f"{sql_str(z['source'])}, {sql_str(json.dumps(geom, separators=(',', ':')))}::jsonb)"
        )
    lines.append(",\n".join(zrows))
    lines.append(
        "on conflict (id) do update set kind = excluded.kind, name = excluded.name, "
        "description = excluded.description, source = excluded.source, area = excluded.area;"
    )
    SEED_OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # Kotlin para o modo demonstração
    kt = [
        "// Gerado por tools/build_areas.py. Não edite à mão.",
        "// Áreas derivadas da linha de costa do OpenStreetMap (© colaboradores do OSM, ODbL).",
        "package com.tuba.data.fake",
        "",
        "internal class SeedBeach(",
        "    val id: String,",
        "    val name: String,",
        "    val city: String,",
        "    val reference: String,",
        "    val lat: Double,",
        "    val lng: Double,",
        "    /** Anel do polígono como \"lat,lng;lat,lng;...\". */",
        "    val ring: String,",
        "    val status: String?,",
        ")",
        "",
        "internal class SeedZone(",
        "    val id: String,",
        "    val kind: String,",
        "    val name: String,",
        "    val description: String,",
        "    val source: String,",
        "    val ring: String,",
        ")",
        "",
        f"/** Data de coleta do boletim mais recente em data/boletins, ou null. */",
        f"internal val seedBulletinDate: String? = {json.dumps(bulletin_date) if bulletin_date else 'null'}",
        "",
        "internal val seedBeaches = listOf(",
    ]
    for b in beaches:
        status = statuses.get(b["id"])
        kt.append(
            f"    SeedBeach({json.dumps(b['id'])}, {json.dumps(b['name'], ensure_ascii=False)}, "
            f"{json.dumps(b['city'], ensure_ascii=False)}, {json.dumps(b['reference'], ensure_ascii=False)}, "
            f"{b['lat']}, {b['lng']}, \"{ring_to_text(b['ring'])}\", {json.dumps(status) if status else 'null'}),"
        )
    kt.append(")")
    kt.append("")
    kt.append("internal val seedZones = listOf(")
    for z in zones:
        kt.append(
            f"    SeedZone({json.dumps(z['id'])}, {json.dumps(z['kind'])}, {json.dumps(z['name'], ensure_ascii=False)}, "
            f"{json.dumps(z['description'], ensure_ascii=False)}, {json.dumps(z['source'], ensure_ascii=False)}, "
            f"\"{ring_to_text(z['ring'])}\"),"
        )
    kt.append(")")
    KOTLIN_OUT.write_text("\n".join(kt).replace("$", "\\$") + "\n", encoding="utf-8")
    print(f"ok: {len(beaches)} trechos, {len(zones)} zonas, boletim {bulletin_date}")


if __name__ == "__main__":
    main()
