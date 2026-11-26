# Política de Seguridad de ResourceHub (`SECURITY.md`)

## 1. Reglas Innegociables de Seguridad en el Repositorio

1. **Cero Secretos en Git:** Queda terminantemente prohibido almacenar contraseñas, claves privadas, `NEON_API_KEY` o archivos `.env` reales en el control de versiones. Antes de cada commit y en cada Pull Request se ejecuta `./scripts/security-scan.sh`.
2. **Separación de Credenciales de Base de Datos (`DTE-PG-04`):**
   - La aplicación ResourceHub se conecta **exclusivamente** con el usuario de mínimo privilegio **`dba_api`** (solo DML y secuencias, sin permisos DDL).
   - El usuario administrativo **`admin`** se utiliza únicamente para ejecutar migraciones versionadas con **Flyway** (`./scripts/flyway-manage.sh`).
3. **Aislamiento de la Base de Datos de Desarrollo frente a CI (`DEP-004`):**
   - Los workflows de GitHub Actions **nunca** se conectan a la base de datos `resourcehub` de Neon ni almacenan sus credenciales en GitHub Secrets.
   - Todas las pruebas de integración en CI utilizan contenedores efímeros y descartables de PostgreSQL 16 (`Testcontainers`).
4. **Confinamiento de Datos Personales (*PII Vault*, `ADR-015`):**
   - Ningún módulo fuera de `MOD-02 acceso` (`access.personal_data` y `access.external_identity`) puede almacenar, registrar en logs ni emitir en eventos datos personales identificables (PII).
5. **Permisos Mínimos en GitHub Actions:**
   - Todos los workflows declaran `permissions: contents: read` por defecto y fijan versiones explícitas de las acciones.
