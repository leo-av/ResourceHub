#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Gestor de Migraciones Flyway sobre PostgreSQL 16 (Neon)
# ==============================================================================
# Uso:
#   ./scripts/flyway-manage.sh [info|migrate|validate|repair]
#
# Descripción (Fase 6):
#   Ejecuta Flyway utilizando exclusivamente el rol administrativo `admin` y el
#   endpoint directo de Neon con TLS (`sslmode=require`), aplicando las
#   migraciones versionadas en `db/migration/`.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/.env"
ACTION="${1:-info}"

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "[ERROR] No se encontró '${ENV_FILE}'. Ejecute primero ./scripts/setup-env.sh." >&2
    exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"

case "${ACTION}" in
    info|migrate|validate|repair)
        ;;
    *)
        echo "Uso: $0 [info|migrate|validate|repair]" >&2
        exit 1
        ;;
esac

FLYWAY_JDBC_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&ApplicationName=resourcehub-flyway-admin"
FLYWAY_SCHEMAS="public,platform_catalog,org_config,access,resources,availability,reservations,policies,traceability,events,integrations,payments"

echo "[FLYWAY] Ejecutando acción '${ACTION}' como usuario '${PG_ADMIN_USER}' contra '${PG_HOST}/${PG_DB}'..."

if command -v flyway >/dev/null 2>&1; then
    flyway \
        -url="${FLYWAY_JDBC_URL}" \
        -user="${PG_ADMIN_USER}" \
        -password="${PG_ADMIN_PASSWORD}" \
        -defaultSchema="public" \
        -schemas="${FLYWAY_SCHEMAS}" \
        -table="flyway_schema_history" \
        -locations="filesystem:${PROJECT_ROOT}/db/migration" \
        -cleanDisabled="true" \
        -validateOnMigrate="true" \
        "${ACTION}"
else
    docker run --rm \
        -v "${PROJECT_ROOT}/db/migration:/flyway/sql:ro" \
        flyway/flyway:10-alpine \
        -url="${FLYWAY_JDBC_URL}" \
        -user="${PG_ADMIN_USER}" \
        -password="${PG_ADMIN_PASSWORD}" \
        -defaultSchema="public" \
        -schemas="${FLYWAY_SCHEMAS}" \
        -table="flyway_schema_history" \
        -locations="filesystem:/flyway/sql" \
        -cleanDisabled="true" \
        -validateOnMigrate="true" \
        "${ACTION}"
fi
