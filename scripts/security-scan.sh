#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Escáner Local y de CI contra Exposición de Secretos y Credenciales
# ==============================================================================
# Uso:
#   ./scripts/security-scan.sh
#
# Verifica:
#   1. Que el archivo `.env` no esté rastreado por Git.
#   2. Que ningún archivo rastreado o preparado para commit contenga tokens de
#      Neon (`napi_`), claves privadas (`BEGIN ... PRIVATE KEY`), tokens de
#      GitHub (`ghp_`, `github_pat_`) ni contraseñas reales en texto plano.
#   3. Si existe `.env` local, comprueba que sus valores secretos (`PG_ADMIN_PASSWORD`,
#      `PG_APP_PASSWORD`, `NEON_API_KEY`) no aparezcan en ningún otro archivo del
#      repositorio.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

echo "[SECURITY-SCAN] Iniciando auditoría de secretos y credenciales en '${PROJECT_ROOT}'..."

# 1. Verificar que .env no esté rastreado por Git
if git ls-files --error-unmatch .env >/dev/null 2>&1; then
    echo "[FAIL] CRÍTICO: El archivo '.env' está rastreado por Git. Ejecute 'git rm --cached .env'." >&2
    exit 1
fi
echo "  [PASS] '.env' no está rastreado en Git (protegido por .gitignore)."

# 2. Escaneo de patrones de secretos conocidos en todos los archivos del repositorio (excepto .env y .git)
SECRET_PATTERNS='(napi_[a-z0-9]{30,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (RSA |EC |OPENSSH |)PRIVATE KEY-----|AKIA[0-9A-Z]{16})'

if rg -n -E "${SECRET_PATTERNS}" . --glob '!.env' --glob '!.env.*' --glob '!.git/**' --glob '!target/**' >/dev/null 2>&1; then
    echo "[FAIL] Se detectaron patrones de credenciales o claves privadas en archivos del repositorio:" >&2
    rg -n -E "${SECRET_PATTERNS}" . --glob '!.env' --glob '!.env.*' --glob '!.git/**' --glob '!target/**' >&2
    exit 1
fi
echo "  [PASS] 0 tokens o claves privadas detectados por patrón en archivos del repositorio."

# 3. Si existe .env local, comprobar que ninguno de sus secretos reales aparezca fuera de .env
if [[ -f "${PROJECT_ROOT}/.env" ]]; then
    # shellcheck disable=SC1090
    source "${PROJECT_ROOT}/.env"
    for secret_var in PG_ADMIN_PASSWORD PG_APP_PASSWORD NEON_API_KEY; do
        secret_val="${!secret_var:-}"
        if [[ -n "${secret_val}" && "${secret_val}" != CHANGE_ME* ]]; then
            if rg -F "${secret_val}" . --glob '!.env' --glob '!.env.*' --glob '!.git/**' --glob '!target/**' >/dev/null 2>&1; then
                echo "[FAIL] El valor real de '${secret_var}' aparece en un archivo versionable del repositorio." >&2
                exit 1
            fi
        fi
    done
    echo "  [PASS] 0 fugas de los secretos activos de '.env' en el resto del repositorio."
fi

echo "[OK] Escaneo de seguridad superado sin hallazgos."
