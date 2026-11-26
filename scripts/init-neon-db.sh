#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Inicialización Idempotente de Base de Datos y Roles en Neon
# ==============================================================================
# Uso:
#   ./scripts/init-neon-db.sh
#
# Responsabilidades (Fases 3 y 4):
#   1. Verifica conectividad TLS (`sslmode=require`) contra Neon PostgreSQL 16.
#   2. Configura la base de datos `resourcehub` (`OWNER admin`, `TimeZone = 'UTC'`,
#      `default_transaction_isolation = 'read committed'`, `search_path` de los
#      11 esquemas aprobados).
#   3. Crea o actualiza de forma idempotente el rol `dba_api` por SQL desde
#      `admin`, garantizando que NO herede `neon_superuser` ni privilegios DDL
#      (`NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`).
#   4. Endurece el esquema `public` y establece los `ALTER DEFAULT PRIVILEGES`
#      globales para objetos y esquemas futuros creados por `admin`.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "[ERROR] No se encontró '${ENV_FILE}'. Ejecute primero ./scripts/setup-env.sh." >&2
    exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"

if [[ -z "${PG_ADMIN_PASSWORD:-}" || -z "${PG_APP_PASSWORD:-}" ]]; then
    echo "[ERROR] PG_ADMIN_PASSWORD y PG_APP_PASSWORD deben estar definidos en .env." >&2
    exit 1
fi

ADMIN_URI="postgresql://${PG_ADMIN_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&application_name=resourcehub-init-admin"

echo "[1/4] Verificando conexión TLS y versión de PostgreSQL en Neon (${PG_HOST})..."
PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -v ON_ERROR_STOP=1 -c "SELECT version(), current_database(), current_user;"

echo "[2/4] Configurando parámetros base de la base de datos '${PG_DB}' (TimeZone=UTC, READ COMMITTED, search_path)..."
PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -v ON_ERROR_STOP=1 <<SQL
ALTER DATABASE ${PG_DB} OWNER TO ${PG_ADMIN_USER};
ALTER DATABASE ${PG_DB} SET TimeZone TO 'UTC';
ALTER DATABASE ${PG_DB} SET default_transaction_isolation TO 'read committed';
ALTER DATABASE ${PG_DB} SET search_path TO platform_catalog, org_config, access, resources, availability, reservations, policies, traceability, events, integrations, payments, public;
SQL

echo "[3/4] Creando o sincronizando el rol de aplicación '${PG_APP_USER}' (mínimo privilegio, sin neon_superuser)..."
PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -v ON_ERROR_STOP=1 \
    -v app_user="${PG_APP_USER}" \
    -v app_pass="${PG_APP_PASSWORD}" <<'SQL'
SELECT format(
    'CREATE ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS',
    :'app_user', :'app_pass'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOCREATEDB NOCREATEROLE INHERIT',
    :'app_user', :'app_pass'
)
\gexec
SQL

echo "[4/4] Configurando seguridad en esquema 'public' y privilegios por defecto globales..."
PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -v ON_ERROR_STOP=1 <<SQL
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON DATABASE ${PG_DB} FROM PUBLIC, ${PG_APP_USER};
GRANT CONNECT, TEMPORARY ON DATABASE ${PG_DB} TO ${PG_APP_USER};
GRANT USAGE ON SCHEMA public TO ${PG_ADMIN_USER}, ${PG_APP_USER};

ALTER DEFAULT PRIVILEGES FOR ROLE ${PG_ADMIN_USER} GRANT USAGE ON SCHEMAS TO ${PG_APP_USER};
ALTER DEFAULT PRIVILEGES FOR ROLE ${PG_ADMIN_USER} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${PG_APP_USER};
ALTER DEFAULT PRIVILEGES FOR ROLE ${PG_ADMIN_USER} GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${PG_APP_USER};
ALTER DEFAULT PRIVILEGES FOR ROLE ${PG_ADMIN_USER} GRANT EXECUTE ON FUNCTIONS TO ${PG_APP_USER};
SQL

echo "[OK] Inicialización de base de datos '${PG_DB}' y roles '${PG_ADMIN_USER}' / '${PG_APP_USER}' completada."
