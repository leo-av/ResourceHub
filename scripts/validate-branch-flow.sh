#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Validador de Política de Flujo de Ramas (DEC-099)
# ==============================================================================
# Reglas innegociables:
#   - `develop` es el entorno estable de PREPRODUCCIÓN.
#   - `main` es el entorno estable de PRODUCCIÓN.
#   - Todo desarrollo normal (no hotfix) debe integrarse mediante PR hacia `develop`.
#   - Hacia `main` SOLO se admiten PRs procedentes de:
#       * `develop` (promoción controlada de Preproducción a Producción tras CI/CD)
#       * `hotfix/*` (corrección urgente productiva, con backmerge posterior a `develop`)
# ==============================================================================
set -euo pipefail

BASE_BRANCH="${1:-}"
HEAD_BRANCH="${2:-}"

if [[ -z "${BASE_BRANCH}" || -z "${HEAD_BRANCH}" ]]; then
    echo "[ERROR] Uso: $0 <base_branch> <head_branch>" >&2
    exit 1
fi

echo "[BRANCH-FLOW] Validando flujo de Pull Request: '${HEAD_BRANCH}' -> '${BASE_BRANCH}'..."

if [[ "${BASE_BRANCH}" == "main" ]]; then
    if [[ "${HEAD_BRANCH}" == "develop" || "${HEAD_BRANCH}" == hotfix/* ]]; then
        echo "  [PASS] Promoción autorizada hacia Producción ('main') desde '${HEAD_BRANCH}'."
        if [[ "${HEAD_BRANCH}" == hotfix/* ]]; then
            echo "  [INFO] Recordatorio obligatorio: tras fusionar este hotfix en 'main', abrir PR de backmerge hacia 'develop'."
        fi
        exit 0
    fi
    echo "  [FAIL] Política de ramas vulnerada: '${HEAD_BRANCH}' NO puede integrarse directamente en 'main' (Producción)." >&2
    echo "         Todos los desarrollos que no sean 'hotfix/*' deben abrir PR hacia 'develop' (Preproducción)." >&2
    echo "         Solo 'develop' o 'hotfix/*' pueden promocionarse hacia 'main'." >&2
    exit 1
fi

if [[ "${BASE_BRANCH}" == "develop" ]]; then
    if [[ "${HEAD_BRANCH}" =~ ^(feat|fix|spec|chore|refactor|test|docs|ci|perf|security|hotfix|dependabot)/.+ ]] || [[ "${HEAD_BRANCH}" == "main" ]]; then
        echo "  [PASS] Integración autorizada hacia Preproducción ('develop') desde '${HEAD_BRANCH}'."
        exit 0
    fi
    echo "  [FAIL] Nombre de rama origen no conforme para integración en 'develop': '${HEAD_BRANCH}'." >&2
    echo "         Prefijos válidos: feat/, fix/, spec/, chore/, refactor/, test/, docs/, ci/, perf/, security/, hotfix/, dependabot/ (o 'main' para backmerge)." >&2
    exit 1
fi

echo "  [PASS] Rama destino no restringida ('${BASE_BRANCH}')."
exit 0
