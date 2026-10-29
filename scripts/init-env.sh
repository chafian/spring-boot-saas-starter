#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; refusing to replace credentials.' >&2
  exit 1
fi
umask 077
{
  for key in POSTGRES_PASSWORD DB_PASSWORD MIGRATION_PASSWORD KEYCLOAK_ADMIN_PASSWORD DEMO_PASSWORD; do
    printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
  done
} > .env
printf 'Created .env with random local-only credentials. Start: docker compose up --build -d\n'
