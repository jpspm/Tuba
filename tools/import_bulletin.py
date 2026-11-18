"""Importa um boletim de balneabilidade da CPRH para o Supabase.

O boletim é transcrito num CSV em data/boletins/AAAA-MM-DD.csv (data da coleta),
com as colunas point_id,status (status: propria | impropria). Linhas com # são comentários.

    python tools/import_bulletin.py data/boletins/2026-11-09.csv
        valida o arquivo e imprime o SQL de upsert

    python tools/import_bulletin.py data/boletins/2026-11-09.csv --apply
        envia direto ao Supabase. Requer SUPABASE_URL e SUPABASE_SERVICE_ROLE_KEY no ambiente.
        A service role ignora RLS: nunca coloque essa chave no app.

Opções: --source "texto" (padrão: "CPRH") e --url URL_DO_BOLETIM.
Somente biblioteca padrão.
"""

import argparse
import csv
import datetime as dt
import json
import os
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POINTS_FILE = ROOT / "data" / "cprh_points.json"
VALID_STATUS = {"propria", "impropria"}


def load_rows(path):
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")]
    return list(csv.DictReader(lines))


def validate(path, rows):
    try:
        sampled_on = dt.date.fromisoformat(path.stem)
    except ValueError:
        sys.exit(f"O nome do arquivo deve ser a data da coleta (AAAA-MM-DD.csv), não '{path.name}'")
    if sampled_on > dt.date.today():
        sys.exit(f"Data de coleta no futuro: {sampled_on}")

    known = {p["id"] for p in json.loads(POINTS_FILE.read_text(encoding="utf-8"))["points"]}
    errors = []
    seen = set()
    for i, row in enumerate(rows, start=2):
        pid, status = (row.get("point_id") or "").strip(), (row.get("status") or "").strip().lower()
        if pid not in known:
            errors.append(f"linha {i}: ponto desconhecido '{pid}'")
        if status not in VALID_STATUS:
            errors.append(f"linha {i}: status inválido '{status}' (use propria ou impropria)")
        if pid in seen:
            errors.append(f"linha {i}: ponto repetido '{pid}'")
        seen.add(pid)
    missing = known - seen
    if missing:
        print(f"Aviso: {len(missing)} ponto(s) fora do boletim: {', '.join(sorted(missing))}", file=sys.stderr)
    if errors:
        sys.exit("\n".join(errors))
    return sampled_on


def to_records(rows, sampled_on, source, url):
    return [
        {
            "beach_id": r["point_id"].strip(),
            "status": r["status"].strip().lower(),
            "sampled_on": sampled_on.isoformat(),
            "source": source,
            "bulletin_url": url,
        }
        for r in rows
    ]


def to_sql(records):
    def lit(v):
        return "null" if v is None else "'" + str(v).replace("'", "''") + "'"

    values = ",\n".join(
        f"  ({lit(r['beach_id'])}, {lit(r['status'])}, {lit(r['sampled_on'])}, {lit(r['source'])}, {lit(r['bulletin_url'])})"
        for r in records
    )
    return (
        "insert into official_readings (beach_id, status, sampled_on, source, bulletin_url) values\n"
        f"{values}\n"
        "on conflict (beach_id, sampled_on) do update set status = excluded.status, "
        "source = excluded.source, bulletin_url = excluded.bulletin_url;"
    )


def apply(records):
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        sys.exit("Defina SUPABASE_URL e SUPABASE_SERVICE_ROLE_KEY")
    req = urllib.request.Request(
        f"{url.rstrip('/')}/rest/v1/official_readings?on_conflict=beach_id,sampled_on",
        data=json.dumps(records).encode(),
        method="POST",
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Prefer": "resolution=merge-duplicates,return=minimal",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        print(f"Supabase respondeu {resp.status}: {len(records)} leituras importadas")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--source", default="CPRH")
    parser.add_argument("--url", default=None)
    args = parser.parse_args()

    rows = load_rows(args.csv)
    sampled_on = validate(args.csv, rows)
    records = to_records(rows, sampled_on, args.source, args.url)
    improper = sum(r["status"] == "impropria" for r in records)
    print(f"Boletim de {sampled_on:%d/%m/%Y}: {len(records)} pontos, {improper} impróprios", file=sys.stderr)
    if args.apply:
        apply(records)
    else:
        print(to_sql(records))


if __name__ == "__main__":
    main()
