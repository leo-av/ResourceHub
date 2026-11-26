#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Generación y Rotación Segura de Credenciales Locales (.env)
# ==============================================================================
# Uso:
#   ./scripts/setup-env.sh [--rotate-dba-api]
#
# Descripción:
#   - Crea o actualiza el archivo local `.env` (con permisos 0600, excluido en
#     `.gitignore`).
#   - Si no existe contraseña para `dba_api`, genera una contraseña aleatoria
#     criptográfica de 24 caracteres alfanuméricos.
#   - Con `--rotate-dba-api`, regenera la contraseña de `dba_api` y si `admin`
#     ya tiene conexión configurada, la actualiza automáticamente en Neon.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/.env"
ROTATE_DBA_API=false

if [[ "${1:-}" == "--rotate-dba-api" ]]; then
    ROTATE_DBA_API=true
fi

generate_password() {
    openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 24
}

umask 077

if [[ -f "${ENV_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
fi

NEON_PROJECT_ID="${NEON_PROJECT_ID:-late-king-57958158}"
NEON_BRANCH_ID="${NEON_BRANCH_ID:-br-falling-dust-bh6j017z}"
NEON_ENDPOINT_ID="${NEON_ENDPOINT_ID:-ep-lively-cherry-bh7xy3tq}"
NEON_REGION="${NEON_REGION:-aws-eu-central-1}"
NEON_API_KEY="${NEON_API_KEY:-}"

PG_HOST="${PG_HOST:-ep-lively-cherry-bh7xy3tq.c-10.eu-central-1.aws.neon.tech}"
PG_POOLER_HOST="${PG_POOLER_HOST:-ep-lively-cherry-bh7xy3tq-pooler.c-10.eu-central-1.aws.neon.tech}"
PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-resourcehub}"
PG_SSLMODE="${PG_SSLMODE:-require}"

PG_ADMIN_USER="${PG_ADMIN_USER:-admin}"
PG_ADMIN_PASSWORD="${PG_ADMIN_PASSWORD:-}"

PG_APP_USER="${PG_APP_USER:-dba_api}"
if [[ -z "${PG_APP_PASSWORD:-}" || "${ROTATE_DBA_API}" == "true" ]]; then
    PG_APP_PASSWORD="$(generate_password)"
    echo "[INFO] Generada nueva contraseña criptográfica para el rol '${PG_APP_USER}'."
fi

if [[ -z "${PG_ADMIN_PASSWORD}" ]]; then
    echo "[WARN] PG_ADMIN_PASSWORD está vacío. Asígnelo en .env con la credencial del rol 'admin' de Neon."
fi

cat > "${ENV_FILE}" <<EOF
# Archivo local de credenciales de ResourceHub (GENERADO AUTOMÁTICAMENTE — NO VERSIONAR)
NEON_PROJECT_ID=${NEON_PROJECT_ID}
NEON_BRANCH_ID=${NEON_BRANCH_ID}
NEON_ENDPOINT_ID=${NEON_ENDPOINT_ID}
NEON_REGION=${NEON_REGION}
NEON_API_KEY=${NEON_API_KEY}

PG_HOST=${PG_HOST}
PG_POOLER_HOST=${PG_POOLER_HOST}
PG_PORT=${PG_PORT}
PG_DB=${PG_DB}
PG_SSLMODE=${PG_SSLMODE}

PG_ADMIN_USER=${PG_ADMIN_USER}
PG_ADMIN_PASSWORD=${PG_ADMIN_PASSWORD}
PG_ADMIN_JDBC_URL=jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&ApplicationName=resourcehub-flyway-admin

PG_APP_USER=${PG_APP_USER}
PG_APP_PASSWORD=${PG_APP_PASSWORD}
PG_APP_JDBC_URL=jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&ApplicationName=resourcehub-app
PG_APP_POOLER_JDBC_URL=jdbc:postgresql://${PG_POOLER_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&ApplicationName=resourcehub-app
EOF

chmod 600 "${ENV_FILE}"
echo "[OK] Archivo '${ENV_FILE}' configurado con permisos 0600."

if [[ "${ROTATE_DBA_API}" == "true" && -n "${PG_ADMIN_PASSWORD}" ]]; then
    PGPASSWORD="${PG_ADMIN_PASSWORD}" psql \
        "postgresql://${PG_ADMIN_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}" \
        -v ON_ERROR_STOP=1 \
        -c "ALTER ROLE ${PG_APP_USER} WITH LOGIN PASSWORD '${PG_APP_PASSWORD}';"
    echo "[OK] Contraseña de '${PG_APP_USER}' rotada en PostgreSQL (Neon)."
fi
