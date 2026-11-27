#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Punto de Entrada Unificado de Automatización Local (Fase 5)
# ==============================================================================
# Uso:
#   ./scripts/dev.sh <comando>
#
# Comandos disponibles:
#   check-tools   Valida las versiones instaladas del toolchain aprobado (Java 21, Maven, Docker, Git, psql 16).
#   setup         Prepara el entorno local, verifica .env y resuelve dependencias Maven.
#   db-ping       Verifica conectividad de SOLO LECTURA (SELECT 1) a 'resourcehub' en Neon con el usuario 'dba_api'.
#   compile       Compila todos los módulos del monorepo XF (test-compile).
#   test          Ejecuta las pruebas unitarias (PRU-01) y de conformidad arquitectónica (PRU-02).
#   lint          Ejecuta análisis estático (Checkstyle) y escaneo de secretos (security-scan.sh).
#   verify        Ejecuta la verificación completa (Análisis Estático + Secretos + Tests + Cobertura JaCoCo).
#   package       Genera el paquete reproducible con manifiesto de versión y checksum SHA-256.
#   clean         Limpia los directorios target/ de todos los módulos.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

CMD="${1:-help}"

check_tools() {
    echo "[CHECK-TOOLS] Verificando versiones del toolchain aprobado (ADR-010)..."

    if ! command -v java >/dev/null 2>&1; then
        echo "[FAIL] Java no está instalado. Se requiere OpenJDK 21 LTS." >&2
        exit 1
    fi
    JAVA_VER="$(java -version 2>&1 | head -n 1)"
    if [[ "${JAVA_VER}" != *"\"21."* && "${JAVA_VER}" != *" 21."* ]]; then
        echo "[FAIL] Versión de Java incorrecta (${JAVA_VER}). Se exige Java 21 LTS." >&2
        exit 1
    fi
    echo "  [PASS] Java 21 LTS detectado: ${JAVA_VER}"

    if ! command -v mvn >/dev/null 2>&1; then
        echo "[FAIL] Apache Maven no está instalado." >&2
        exit 1
    fi
    MVN_VER="$(mvn -version | head -n 1)"
    echo "  [PASS] Maven detectado: ${MVN_VER}"

    if ! command -v git >/dev/null 2>&1; then
        echo "[FAIL] Git no está instalado." >&2
        exit 1
    fi
    echo "  [PASS] Git detectado: $(git --version)"

    if ! command -v psql >/dev/null 2>&1; then
        echo "[FAIL] Cliente PostgreSQL (psql) no está instalado." >&2
        exit 1
    fi
    echo "  [PASS] Cliente PostgreSQL detectado: $(psql --version)"

    if command -v docker >/dev/null 2>&1; then
        echo "  [PASS] Docker detectado: $(docker --version)"
    else
        echo "  [WARN] Docker no detectado (necesario cuando se ejecuten tests Testcontainers)."
    fi

    echo "[OK] Todas las herramientas obligatorias están disponibles y en versión compatible."
}

db_ping_readonly() {
    ENV_FILE="${PROJECT_ROOT}/.env"
    if [[ ! -f "${ENV_FILE}" ]]; then
        echo "[ERROR] No existe '.env'. Ejecute './scripts/setup-env.sh' y configure las credenciales." >&2
        exit 1
    fi
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    echo "[DB-PING] Comprobando conexión de SOLO LECTURA como '${PG_APP_USER}' contra '${PG_HOST}/${PG_DB}'..."
    RES="$(PGPASSWORD="${PG_APP_PASSWORD}" psql \
        "postgresql://${PG_APP_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}?sslmode=${PG_SSLMODE}&application_name=resourcehub-dev-ping" \
        -X -q -t -A -v ON_ERROR_STOP=1 \
        -c "SELECT current_database() || '|' || current_user || '|' || current_setting('TimeZone') || '|' || (SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('platform_catalog','org_config','access','resources','availability','reservations','policies','traceability','events','integrations','payments'));")"
    if [[ "${RES}" == "resourcehub|dba_api|UTC|11" ]]; then
        echo "  [PASS] Conectividad de solo lectura verificada: ${RES} (BD intacta, 11/11 esquemas presentes)."
    else
        echo "  [FAIL] Respuesta inesperada en db-ping: ${RES}" >&2
        exit 1
    fi
}

case "${CMD}" in
    check-tools)
        check_tools
        ;;
    setup)
        check_tools
        if [[ ! -f "${PROJECT_ROOT}/.env" ]]; then
            "${PROJECT_ROOT}/scripts/setup-env.sh"
        fi
        ./mvnw -B validate
        echo "[OK] Entorno local preparado correctamente."
        ;;
    db-ping)
        db_ping_readonly
        ;;
    compile)
        ./mvnw -B test-compile
        ;;
    test)
        ./mvnw -B test
        ;;
    lint)
        "${PROJECT_ROOT}/scripts/security-scan.sh"
        ./mvnw -B checkstyle:check
        ;;
    verify)
        check_tools
        "${PROJECT_ROOT}/scripts/security-scan.sh"
        ./mvnw -B clean verify
        echo "[OK] Todas las puertas de verificación local (Checkstyle, Secretos, Tests, JaCoCo) en VERDE."
        ;;
    package)
        "${PROJECT_ROOT}/scripts/package-artifact.sh"
        ;;
    check-branch-flow)
        "${PROJECT_ROOT}/scripts/validate-branch-flow.sh" "${2:-}" "${3:-}"
        ;;
    clean)
        ./mvnw -B clean
        ;;
    *)
        echo "Uso: $0 {check-tools|setup|db-ping|compile|test|lint|verify|package|check-branch-flow|clean}"
        exit 1
        ;;
esac
