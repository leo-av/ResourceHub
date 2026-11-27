# ResourceHub — Monolito Modular XF y API Gateway de Borde

Repositorio oficial de código de **ResourceHub** (`Java 21 LTS`, `Spring Boot 4.2`, `Spring Modulith 2.2`, `PostgreSQL 16+`, `Flyway 13`, `JUnit 6`, `Testcontainers 2`, `ArchUnit 1.5`, **Arquitectura XF**).

> [!IMPORTANT]
> **Fuente única de verdad documental (`AGENTS.md`):**
> Toda la documentación funcional, arquitectónica, de diseño de datos, estándares SDD/TDD, backlog técnico y guías operativas reside y se versiona exclusivamente en la carpeta maestra de **Google Drive (`ResourceHub/docs/` y `ResourceHub/01-implementation/`)**. Este repositorio no almacena copias locales de dichos documentos.

---

## 1. Estructura del Monorepo

- `.agents/skills/`: Las 6 SKILL operativas del proyecto (`resourcehub-development-workflow`, `resourcehub-sdd`, `resourcehub-tdd`, `xf-architecture`, `response-wrapper`, `document-version-management`).
- `.github/`: Workflows de CI/CD (`ci-pr.yml`, `ci-develop.yml`, `ci-main.yml`, `ci-maintenance.yml`), Dependabot (dirigido a `develop`) y plantillas de Issues/PRs.
- `.idea/` y `.editorconfig`: Configuración compartida para **IntelliJ IDEA** (UTF-8, Java 21, estilo sin wildcard imports y Run Configuration de verificación).
- `config/`: Reglas de análisis estático (`checkstyle.xml`) y plantillas `.example` de conexión a base de datos y Flyway.
- `db/migration/`: Migraciones versionadas de Flyway sobre PostgreSQL 16+.
- `modules/`: Los 12 módulos del reactor Maven (`mod-11-shared`, `comp-gw-gateway` y `mod-01-platform` a `mod-10-payments`).
- `scripts/`: Automatización local (`dev.sh`, `validate-branch-flow.sh`, `setup-env.sh`, `init-neon-db.sh`, `flyway-manage.sh`, `validate-environment.sh`, `security-scan.sh`, `package-artifact.sh`).

---

## 2. Flujo de Ramas e Integración (`DEC-099`)

- **`develop` (Entorno Estable de Preproducción):** Todas las ramas de desarrollo (`feat/*`, `fix/*`, `spec/*`, `chore/*`, `refactor/*`, `test/*`, `dependabot/*`) nacen desde `develop` y se integran mediante Pull Request hacia `develop`.
- **`main` (Entorno Estable de Producción):** Solo recibe promociones desde `develop` (tras superar `ci-develop.yml` y validaciones acordes en Preproducción) o correcciones urgentes `hotfix/*` (con backmerge obligatorio posterior hacia `develop`).

---

## 3. Comandos Rápidos de Desarrollo (`./scripts/dev.sh` o `Makefile`)

```bash
# 1. Verificar versiones de herramientas (Java 21, Maven, Docker, Git, psql 16)
./scripts/dev.sh check-tools

# 2. Preparar el entorno local
./scripts/dev.sh setup

# 3. Comprobar conectividad de solo lectura (dba_api) contra PostgreSQL 16 en Neon
./scripts/dev.sh db-ping

# 4. Ejecutar análisis estático (Checkstyle) y escaneo de secretos
./scripts/dev.sh lint

# 5. Ejecutar todas las puertas de calidad (Checkstyle + Secretos + Tests + JaCoCo)
./scripts/dev.sh verify

# 6. Validar la política de flujo de ramas (base vs head)
./scripts/dev.sh check-branch-flow develop feat/TT-001-mod11-shared-primitives

# 7. Generar paquete reproducible con manifiesto y checksum SHA-256
./scripts/dev.sh package
```
