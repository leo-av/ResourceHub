# Guía de Contribución y Flujo SDD + TDD en ResourceHub

Toda contribución al repositorio de **ResourceHub** (humana o mediante agentes de IA) está gobernada por las SKILL en `.agents/skills/` y por los estándares aprobados en Google Drive (`ResourceHub/docs/`).

---

## 1. Flujo de Trabajo Obligatorio (10 Pasos)

1. **Selección determinista en el DAG:** Activar `.agents/skills/resourcehub-development-workflow/SKILL.md` para seleccionar una unidad con `Approval = APPROVED` cuyas dependencias previas `TT-*` estén en `DONE`.
2. **Especificación SDD (`resourcehub-sdd`):** Verificar o elaborar la ficha `SPEC-<MOD>-<NNN>` en sus 15 secciones y superar las puertas `QG-01` y `QG-02`.
3. **Aprobación humana de cambios o bloqueos:** Si concurre cualquiera de las 8 condiciones *Stop-the-Line* (`STL-01..08`), detenerse y solicitar decisión del propietario.
4. **Creación de rama desde `develop` (entorno estable de Preproducción, `DEC-099`):**
   - `feat/TT-XXX-<slug>` para tareas técnicas e historias → PR hacia **`develop`**.
   - `spec/SPEC-YYY-NNN-<slug>` para especificaciones SDD → PR hacia **`develop`**.
   - `fix/<ID>-<slug>` para correcciones de defectos → PR hacia **`develop`**.
   - `chore/<alcance>-<slug>` para mantenimiento/CI/dependencias → PR hacia **`develop`**.
   - **Única excepción (`hotfix/*`):** solo para correcciones urgentes sobre Producción nacidas desde `main` / tag `v*` → PR hacia **`main`** seguida de **backmerge obligatorio hacia `develop`**.
5. **Construcción TDD por capas XF (`resourcehub-tdd`):** Iterar `RED -> GREEN -> REFACTOR` en orden `repository` (`R`) -> `business` (`B`, solo ENUM `ErrorCode`) -> `api` (`A`) -> `COMP-GW`, respetando las **50 reglas XF** y las **8 reglas ResourceHub**.
6. **Validación local completa:** Ejecutar `./scripts/dev.sh verify` (Checkstyle, escaneo de secretos, tests unitarios/integración y cobertura JaCoCo $\ge 90\%$ en `business/logic`).
7. **Convención de Commits:**
   ```text
   <tipo>(<módulo-o-TT>): <descripción en imperativo> [TT-XXX] [SPEC-YYY-NNN]
   ```
8. **Pull Request hacia `develop` con las 7 Evidencias (`QG-06`):** Completar la plantilla `.github/pull_request_template.md` abriendo la PR contra **`develop`** (Preproducción).
9. **Ejecución de CI/CD en GitHub Actions:**
   - En la PR hacia `develop`: `ci-pr.yml` valida el flujo de ramas (`scripts/validate-branch-flow.sh`) y todas las puertas `Gate 1-5`.
   - Tras aprobar y fusionar en `develop`, `ci-develop.yml` certifica Preproducción y genera el artefacto reproducible de Preproducción.
10. **Promoción controlada a Producción (`develop` → `main`):** Solo tras superar CI/CD y validaciones acordes en `develop` (Preproducción), se abre PR de `develop` hacia `main` (Producción). Tras su aprobación y merge, `ci-main.yml` genera el artefacto productivo y se etiqueta SemVer si cierra hito/MVP.
