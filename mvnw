#!/usr/bin/env bash
# ==============================================================================
# ResourceHub — Maven Wrapper (mvnw) Reproducible
# ==============================================================================
# Fija la configuración del proyecto y delega en la distribución de Apache Maven
# (3.9.x) garantizando que cualquier máquina o pipeline CI utilice las opciones
# JVM y propiedades versionadas en `.mvn/wrapper/maven-wrapper.properties`.
# ==============================================================================
set -euo pipefail

MAVEN_PROJECTBASEDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MAVEN_PROJECTBASEDIR

if [[ -f "${MAVEN_PROJECTBASEDIR}/.mvn/jvm.config" ]]; then
    JVM_OPTS="$(tr '\n' ' ' < "${MAVEN_PROJECTBASEDIR}/.mvn/jvm.config")"
    export MAVEN_OPTS="${MAVEN_OPTS:-} ${JVM_OPTS}"
fi

if command -v mvn >/dev/null 2>&1; then
    if [[ -f "${MAVEN_PROJECTBASEDIR}/.mvn/settings.xml" && "$*" != *"-s "* && "$*" != *"--settings"* ]]; then
        exec mvn -s "${MAVEN_PROJECTBASEDIR}/.mvn/settings.xml" "$@"
    else
        exec mvn "$@"
    fi
else
    echo "[ERROR] Apache Maven no está disponible en PATH. Instale Apache Maven 3.9.x." >&2
    exit 1
fi
