#!/usr/bin/env sh
# Sobe um Postgres descartável no Docker, aplica migrations + seeds e roda os testes de regras.
# Uso: sh tools/test_db.sh
set -eu

cd "$(dirname "$0")/.."
NAME=tuba-test-db

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=postgres postgres:16 >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2

run() {
  echo "-> $1"
  docker exec -i "$NAME" psql -q -v ON_ERROR_STOP=1 -U postgres < "$1"
}

run supabase/tests/00_stub_supabase.sql
for f in supabase/migrations/*.sql; do run "$f"; done
run supabase/seed.sql
for f in supabase/readings_*.sql; do run "$f"; done
run supabase/tests/10_grants.sql
run supabase/tests/20_rules_test.sql
echo "Todos os testes do banco passaram."
