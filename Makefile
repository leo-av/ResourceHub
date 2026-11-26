# ==============================================================================
# ResourceHub — Makefile de Automatización Local
# ==============================================================================
.PHONY: help check-tools setup db-ping compile test lint verify package clean

help:
	@echo "Comandos disponibles en ResourceHub:"
	@echo "  make check-tools  - Verifica versiones de Java 21, Maven, Docker, Git y psql 16"
	@echo "  make setup        - Prepara el entorno local y valida el reactor Maven"
	@echo "  make db-ping      - Verifica conexión de solo lectura (dba_api) contra PostgreSQL en Neon"
	@echo "  make compile      - Compila los 12 módulos del monorepo XF"
	@echo "  make test         - Ejecuta pruebas unitarias (PRU-01) y arquitectónicas (PRU-02)"
	@echo "  make lint         - Ejecuta escáner de secretos y análisis estático Checkstyle"
	@echo "  make verify       - Ejecuta todas las puertas de calidad local (QG-05)"
	@echo "  make package      - Genera artefacto reproducible con checksum SHA-256"
	@echo "  make clean        - Limpia artefactos de compilación"

check-tools:
	./scripts/dev.sh check-tools

setup:
	./scripts/dev.sh setup

db-ping:
	./scripts/dev.sh db-ping

compile:
	./scripts/dev.sh compile

test:
	./scripts/dev.sh test

lint:
	./scripts/dev.sh lint

verify:
	./scripts/dev.sh verify

package:
	./scripts/dev.sh package

clean:
	./scripts/dev.sh clean
