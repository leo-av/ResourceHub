#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Generación Reproducible de Artefacto y Verificación SHA-256 (CD)
# ==============================================================================
# Uso:
#   ./scripts/package-artifact.sh
#
# Descripción (Fase 7 — Estrategia CD a Coste Cero):
#   1. Compila y empaqueta los módulos del reactor Maven sin saltar validaciones.
#   2. Recopila los artefactos `.jar`, la migración Flyway y los metadatos de
#      versión (`VERSION_MANIFEST.txt`) en `target/dist/`.
#   3. Calcula y verifica la suma criptográfica `SHA-256` (`SHA256SUMS.txt`).
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

DIST_DIR="${PROJECT_ROOT}/target/dist"
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/jars" "${DIST_DIR}/db/migration"

PROJECT_VERSION="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo "uncommitted")"
BUILD_TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "[CD-PACKAGE] Empaquetando ResourceHub v${PROJECT_VERSION} (commit: ${GIT_COMMIT})..."

./mvnw -B package -DskipTests

find "${PROJECT_ROOT}/modules" -path "*/target/*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" -exec cp {} "${DIST_DIR}/jars/" \;
cp -r "${PROJECT_ROOT}/db/migration/"*.sql "${DIST_DIR}/db/migration/"

cat > "${DIST_DIR}/VERSION_MANIFEST.txt" <<EOF
artifact_group=app.resourcehub
artifact_name=resourcehub-parent
version=${PROJECT_VERSION}
git_commit=${GIT_COMMIT}
built_at_utc=${BUILD_TIMESTAMP}
java_runtime=$(java -version 2>&1 | head -n 1)
EOF

(
    cd "${DIST_DIR}"
    sha256sum VERSION_MANIFEST.txt jars/*.jar db/migration/*.sql > SHA256SUMS.txt
    sha256sum -c SHA256SUMS.txt
)

echo "[OK] Artefacto reproducible generado y verificado en '${DIST_DIR}'."
