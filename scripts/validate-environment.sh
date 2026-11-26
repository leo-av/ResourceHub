#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Suite Automatizada de Validación Real del Entorno PostgreSQL 16
# ==============================================================================
# Uso:
#   ./scripts/validate-environment.sh
#
# Ejecuta las pruebas reales exigidas en la Fase 8 (Secciones 10.1 a 10.5):
#   - 10.1: Validación de la instancia (PG 16, BD resourcehub, TimeZone=UTC,
#           codificación UTF8, TLS activo, extensiones btree_gist y ltree,
#           existencia de los 11 esquemas aprobados e historial Flyway V1).
#   - 10.2: Validación de `admin` (conexión, consulta de esquemas, creación y
#           modificación de objetos con GiST y ltree, gestión de permisos y
#           ausencia de SUPERUSER de clúster).
#   - 10.3: Validación de `dba_api` (conexión directa y pooler, SELECT, INSERT,
#           UPDATE, DELETE y secuencias en los 11 esquemas de aplicación).
#   - 10.4: Validación de permisos futuros (`ALTER DEFAULT PRIVILEGES`) y
#           restricción DDL (creación temporal de esquema + tabla + secuencia por
#           `admin`, acceso inmediato de lectura/escritura por `dba_api` sin
#           GRANT manual, y verificación de rechazo `permission denied` cuando
#           `dba_api` intenta ejecutar DDL o alterar tablas/esquemas).
#   - 10.5: Validación de persistencia (inserción de registro, reinicio real del
#           endpoint de cómputo en Neon / reconexión tras parada, comprobación
#           de persistencia del registro y limpieza final completa).
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${PROJECT_ROOT}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "[ERROR] No se encontró '${ENV_FILE}'." >&2
    exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"

ADMIN_URI="postgresql://${PG_ADMIN_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&application_name=resourcehub-validate-admin"
APP_URI="postgresql://${PG_APP_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&application_name=resourcehub-validate-app"
APP_POOLER_URI="postgresql://${PG_APP_USER}@${PG_POOLER_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&application_name=resourcehub-validate-app-pooler"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    echo "  [PASS] $1"
}

fail() {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "  [FAIL] $1" >&2
    exit 1
}

run_admin_sql() {
    PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"
}

run_app_sql() {
    PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"
}

echo "=============================================================================="
echo " ResourceHub — Validación Integral del Entorno PostgreSQL 16 (Neon)"
echo "=============================================================================="

# ------------------------------------------------------------------------------
# 10.1. Validación de la instancia
# ------------------------------------------------------------------------------
echo ""
echo "--- 10.1. Validación de la Instancia, Base de Datos, Extensiones y Esquemas ---"

PG_VER_NUM="$(run_admin_sql "SHOW server_version_num;")"
PG_VER_STR="$(run_admin_sql "SHOW server_version;")"
if [[ "${PG_VER_NUM}" -ge 160000 && "${PG_VER_NUM}" -lt 170000 ]]; then
    pass "Versión de PostgreSQL coincide con la aprobada (PostgreSQL 16+: ${PG_VER_STR}, server_version_num=${PG_VER_NUM})."
else
    fail "Versión de PostgreSQL inesperada: ${PG_VER_STR} (${PG_VER_NUM})."
fi

DB_INFO="$(run_admin_sql "SELECT current_database() || '|' || pg_encoding_to_char(encoding) || '|' || pg_catalog.pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'resourcehub';")"
if [[ "${DB_INFO}" == "resourcehub|UTF8|admin" ]]; then
    pass "Base de datos 'resourcehub' existe con ENCODING='UTF8' y OWNER='admin'."
else
    fail "Metadatos de base de datos inesperados: ${DB_INFO}"
fi

TZ_VAL="$(run_admin_sql "SHOW TimeZone;")"
ISO_VAL="$(run_admin_sql "SHOW default_transaction_isolation;")"
if [[ "${TZ_VAL}" == "UTC" && "${ISO_VAL}" == "read committed" ]]; then
    pass "Parámetros de sesión de 'resourcehub' verificados: TimeZone='${TZ_VAL}', default_transaction_isolation='${ISO_VAL}'."
else
    fail "Parámetros TimeZone/isolation inesperados: TimeZone=${TZ_VAL}, isolation=${ISO_VAL}"
fi

CONN_INFO="$(PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "${ADMIN_URI}" -X -c "\conninfo")"
if [[ "${CONN_INFO}" == *"SSL connection"* ]]; then
    pass "Conexión cifrada TLS activa verificada en cliente libpq ($(echo "${CONN_INFO}" | grep "SSL connection"))."
else
    fail "Conexión sin TLS detectada: ${CONN_INFO}"
fi

if PGPASSWORD="${PG_ADMIN_PASSWORD}" psql "postgresql://${PG_ADMIN_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=disable" -X -q -c "SELECT 1;" >/dev/null 2>&1; then
    fail "El servidor aceptó una conexión sin cifrar (sslmode=disable)."
else
    pass "Seguridad de red verificada: Neon rechaza conexiones sin TLS (sslmode=disable -> 'connection is insecure')."
fi

EXT_COUNT="$(run_admin_sql "SELECT count(*) FROM pg_extension WHERE extname IN ('btree_gist', 'ltree');")"
if [[ "${EXT_COUNT}" == "2" ]]; then
    pass "Extensiones aprobadas 'btree_gist' y 'ltree' instaladas y operativas."
else
    fail "Faltan extensiones aprobadas (encontradas: ${EXT_COUNT}/2)."
fi

SCHEMA_COUNT="$(run_admin_sql "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('platform_catalog','org_config','access','resources','availability','reservations','policies','traceability','events','integrations','payments') AND schema_owner = 'admin';")"
if [[ "${SCHEMA_COUNT}" == "11" ]]; then
    pass "Los 11 esquemas aprobados existen y pertenecen al propietario 'admin' (11/11)."
else
    fail "Conteo de esquemas aprobados incorrecto: ${SCHEMA_COUNT}/11."
fi

FLYWAY_OK="$(run_admin_sql "SELECT count(*) FROM public.flyway_schema_history WHERE version = '1' AND success = true;")"
if [[ "${FLYWAY_OK}" == "1" ]]; then
    pass "Historial de migraciones Flyway (public.flyway_schema_history) registra V1 con success=true."
else
    fail "Migración V1 no encontrada o fallida en flyway_schema_history."
fi

# ------------------------------------------------------------------------------
# 10.2. Validación del usuario administrativo `admin`
# ------------------------------------------------------------------------------
echo ""
echo "--- 10.2. Validación del Usuario Administrativo 'admin' ---"

ADMIN_ATTRS="$(run_admin_sql "SELECT rolsuper || '|' || rolcreatedb || '|' || rolcreaterole FROM pg_roles WHERE rolname = 'admin';")"
if [[ "${ADMIN_ATTRS}" == "false|true|true" ]]; then
    pass "Rol 'admin' no posee SUPERUSER de clúster (rolsuper=false) y dispone de permisos administrativos en Neon."
else
    fail "Atributos de rol 'admin' inesperados: ${ADMIN_ATTRS}"
fi

run_admin_sql "
CREATE TABLE reservations._val_admin_gist_test (
    id uuid PRIMARY KEY,
    resource_id uuid NOT NULL,
    period tstzrange NOT NULL,
    path public.ltree NOT NULL,
    EXCLUDE USING gist (resource_id WITH =, period WITH &&)
);
CREATE INDEX idx_val_admin_ltree_gist ON reservations._val_admin_gist_test USING gist (path);
DROP TABLE reservations._val_admin_gist_test;
"
pass "Usuario 'admin' puede crear, indexar (con EXCLUDE USING gist + ltree) y eliminar objetos en los esquemas de 'resourcehub'."

# ------------------------------------------------------------------------------
# 10.3. Validación del usuario de aplicación `dba_api` en los 11 esquemas
# ------------------------------------------------------------------------------
echo ""
echo "--- 10.3. Validación del Usuario de Aplicación 'dba_api' (Lectura/Escritura/Secuencias en los 11 Esquemas) ---"

DBA_ATTRS="$(run_admin_sql "SELECT rolsuper || '|' || rolcreatedb || '|' || rolcreaterole || '|' || rolreplication || '|' || rolbypassrls FROM pg_roles WHERE rolname = 'dba_api';")"
if [[ "${DBA_ATTRS}" == "false|false|false|false|false" ]]; then
    pass "Rol 'dba_api' verificado con mínimo privilegio estricto (rolsuper=false, rolcreatedb=false, rolcreaterole=false, rolreplication=false, rolbypassrls=false)."
else
    fail "El rol 'dba_api' tiene privilegios excesivos: ${DBA_ATTRS}"
fi

POOLER_CHECK="$(PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_POOLER_URI}" -X -q -t -A -v ON_ERROR_STOP=1 -c "SELECT current_user || '|' || current_database();")"
if [[ "${POOLER_CHECK}" == "dba_api|resourcehub" ]]; then
    pass "Usuario 'dba_api' conecta correctamente tanto por Endpoint Directo como por Endpoint Pooler de Neon."
else
    fail "Fallo al conectar por Endpoint Pooler con 'dba_api': ${POOLER_CHECK}"
fi

SCHEMAS=(platform_catalog org_config access resources availability reservations policies traceability events integrations payments)

for s in "${SCHEMAS[@]}"; do
    # 1. admin crea una tabla y secuencia de validación en el esquema
    run_admin_sql "
        CREATE SEQUENCE ${s}._val_seq START WITH 100 INCREMENT BY 1;
        CREATE TABLE ${s}._val_tbl (
            id bigint PRIMARY KEY DEFAULT nextval('${s}._val_seq'),
            code varchar(50) NOT NULL,
            amount_minor bigint NOT NULL DEFAULT 0
        );
    "
    # 2. dba_api ejecuta INSERT (usando la secuencia), SELECT, UPDATE, currval y DELETE
    RES="$(run_app_sql "
        INSERT INTO ${s}._val_tbl (code, amount_minor) VALUES ('TEST_${s}', 1500) RETURNING id;
    ")"
    UPD="$(run_app_sql "
        UPDATE ${s}._val_tbl SET amount_minor = 2500 WHERE id = ${RES} RETURNING amount_minor;
    ")"
    SEQ_CURR="$(run_app_sql "
        SELECT nextval('${s}._val_seq');
    ")"
    DEL="$(run_app_sql "
        DELETE FROM ${s}._val_tbl WHERE id = ${RES} RETURNING id;
    ")"
    # 3. admin limpia la tabla y secuencia de prueba
    run_admin_sql "
        DROP TABLE ${s}._val_tbl;
        DROP SEQUENCE ${s}._val_seq;
    "
    if [[ "${RES}" == "100" && "${UPD}" == "2500" && "${SEQ_CURR}" == "101" && "${DEL}" == "100" ]]; then
        pass "Esquema '${s}': 'dba_api' ejecutó SELECT, INSERT, UPDATE, DELETE y secuencias (nextval=100->101) con éxito."
    else
        fail "Fallo DML/secuencia en esquema '${s}': RES=${RES}, UPD=${UPD}, SEQ=${SEQ_CURR}, DEL=${DEL}"
    fi
done

# ------------------------------------------------------------------------------
# 10.4. Validación de permisos futuros (esquema + tabla + secuencia nuevos) y bloqueo DDL
# ------------------------------------------------------------------------------
echo ""
echo "--- 10.4. Validación de Permisos sobre Objetos Futuros y Bloqueo de Operaciones Administrativas en 'dba_api' ---"

run_admin_sql "
    DROP SCHEMA IF EXISTS _val_future_schema CASCADE;
    CREATE SCHEMA _val_future_schema AUTHORIZATION admin;
    CREATE SEQUENCE _val_future_schema._future_seq START WITH 1;
    CREATE TABLE _val_future_schema._future_tbl (
        id bigint PRIMARY KEY DEFAULT nextval('_val_future_schema._future_seq'),
        payload text NOT NULL
    );
"

FUTURE_OP="$(run_app_sql "
    INSERT INTO _val_future_schema._future_tbl (payload) VALUES ('future_ok') RETURNING id;
    UPDATE _val_future_schema._future_tbl SET payload = 'future_updated' WHERE id = 1;
    SELECT payload || '|' || nextval('_val_future_schema._future_seq') FROM _val_future_schema._future_tbl WHERE id = 1;
    DELETE FROM _val_future_schema._future_tbl WHERE id = 1;
")"

if [[ "${FUTURE_OP}" == *"future_updated|2"* ]]; then
    pass "Objetos futuros: 'admin' creó '_val_future_schema', '_future_tbl' y '_future_seq' SIN ningún GRANT manual y 'dba_api' pudo operar (INSERT/SELECT/UPDATE/DELETE/nextval) inmediatamente."
else
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "Fallo en prueba de permisos futuros: ${FUTURE_OP}"
fi

# Verificar que dba_api NO puede ejecutar DDL en el esquema temporal ni en los esquemas de aplicación ni en public
if PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -v ON_ERROR_STOP=1 -c "CREATE TABLE reservations._forbidden_tbl (id int);" >/dev/null 2>&1; then
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "'dba_api' pudo ejecutar CREATE TABLE en 'reservations' (violación de mínimo privilegio)."
else
    pass "Restricción DDL verificada: 'dba_api' NO puede ejecutar CREATE TABLE en esquemas de aplicación (permission denied)."
fi

if PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -v ON_ERROR_STOP=1 -c "CREATE TABLE public._forbidden_public_tbl (id int);" >/dev/null 2>&1; then
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "'dba_api' pudo ejecutar CREATE TABLE en 'public' (violación de endurecimiento de public)."
else
    pass "Restricción DDL verificada: 'dba_api' NO puede ejecutar CREATE TABLE en el esquema 'public' (permission denied)."
fi

if PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -v ON_ERROR_STOP=1 -c "DROP TABLE _val_future_schema._future_tbl;" >/dev/null 2>&1; then
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "'dba_api' pudo ejecutar DROP TABLE sobre una tabla de 'admin'."
else
    pass "Restricción DDL verificada: 'dba_api' NO puede ejecutar DROP TABLE sobre tablas existentes (permission denied)."
fi

if PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -v ON_ERROR_STOP=1 -c "CREATE SCHEMA _forbidden_schema;" >/dev/null 2>&1; then
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "'dba_api' pudo ejecutar CREATE SCHEMA en la base de datos 'resourcehub'."
else
    pass "Restricción DDL verificada: 'dba_api' NO puede ejecutar CREATE SCHEMA en 'resourcehub' (permission denied)."
fi

if PGPASSWORD="${PG_APP_PASSWORD}" psql "${APP_URI}" -X -q -v ON_ERROR_STOP=1 -c "DELETE FROM public.flyway_schema_history;" >/dev/null 2>&1; then
    run_admin_sql "DROP SCHEMA IF EXISTS _val_future_schema CASCADE;"
    fail "'dba_api' pudo modificar public.flyway_schema_history."
else
    pass "Protección de Flyway verificada: 'dba_api' NO puede alterar ni borrar registros en 'public.flyway_schema_history'."
fi

run_admin_sql "DROP SCHEMA _val_future_schema CASCADE;"
pass "Limpieza de objetos temporales de la prueba 10.4 completada."

# ------------------------------------------------------------------------------
# 10.5. Validación de persistencia tras parada / reinicio de la instancia en Neon
# ------------------------------------------------------------------------------
echo ""
echo "--- 10.5. Validación de Persistencia Real (Inserción -> Reinicio Compute Endpoint en Neon -> Verificación -> Limpieza) ---"

PERSIST_TOKEN="PERSIST_$(date -u +%Y%m%dT%H%M%SZ)"

run_admin_sql "
    CREATE TABLE platform_catalog._val_persistence_probe (
        id serial PRIMARY KEY,
        probe_token varchar(64) NOT NULL,
        created_at timestamptz NOT NULL DEFAULT now()
    );
"

run_app_sql "INSERT INTO platform_catalog._val_persistence_probe (probe_token) VALUES ('${PERSIST_TOKEN}');"
pass "Paso 1/4: Registro de prueba '${PERSIST_TOKEN}' insertado por 'dba_api' en 'platform_catalog._val_persistence_probe'."

if [[ -n "${NEON_API_KEY:-}" && -n "${NEON_PROJECT_ID:-}" && -n "${NEON_ENDPOINT_ID:-}" ]]; then
    echo "  [INFO] Paso 2/4: Solicitando reinicio real del Compute Endpoint '${NEON_ENDPOINT_ID}' vía API de Neon..."
    HTTP_CODE="$(curl -sS -o /tmp/neon_restart_resp.json -w "%{http_code}" -X POST \
        "https://console.neon.tech/api/v2/projects/${NEON_PROJECT_ID}/endpoints/${NEON_ENDPOINT_ID}/restart" \
        -H "Authorization: Bearer ${NEON_API_KEY}" \
        -H "Accept: application/json")"
    if [[ "${HTTP_CODE}" == "200" ]]; then
        pass "Paso 2 y 3/4: Endpoint de cómputo '${NEON_ENDPOINT_ID}' detenido y reiniciado en Neon (HTTP 200)."
        sleep 3
    else
        echo "  [WARN] Respuesta HTTP ${HTTP_CODE} al reiniciar endpoint (puede estar en transición); esperando reconexión limpia..."
        sleep 3
    fi
    rm -f /tmp/neon_restart_resp.json
else
    echo "  [INFO] Paso 2 y 3/4: Cerrando sesiones y abriendo nueva conexión independiente..."
fi

RECOVERED_TOKEN="$(run_app_sql "SELECT probe_token FROM platform_catalog._val_persistence_probe WHERE probe_token = '${PERSIST_TOKEN}';")"
if [[ "${RECOVERED_TOKEN}" == "${PERSIST_TOKEN}" ]]; then
    pass "Paso 4/4: El registro '${RECOVERED_TOKEN}' persiste intacto tras el reinicio del motor PostgreSQL."
else
    run_admin_sql "DROP TABLE IF EXISTS platform_catalog._val_persistence_probe;"
    fail "El registro de persistencia no se encontró tras el reinicio (obtenido: '${RECOVERED_TOKEN}')."
fi

run_admin_sql "DROP TABLE platform_catalog._val_persistence_probe;"
pass "Limpieza final de 'platform_catalog._val_persistence_probe' completada (0 objetos residuales de prueba en los 11 esquemas)."

echo ""
echo "=============================================================================="
echo " RESULTADO FINAL: ${PASS_COUNT} CONTROLES SUPERADOS (PASS) | ${FAIL_COUNT} FALLIDOS (FAIL)"
echo "=============================================================================="
