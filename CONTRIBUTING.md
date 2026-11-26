# Guía de Contribución y Flujo SDD + TDD en ResourceHub

Toda contribución al repositorio de **ResourceHub** (humana o mediante agentes de IA) está gobernada por las SKILL en `.agents/skills/` y por los estándares aprobados en Google Drive (`ResourceHub/docs/`).

---

## 1. Flujo de Trabajo Obligatorio (10 Pasos)

1. **Selección determinista en el DAG:** Activar `.agents/skills/resourcehub-development-workflow/SKILL.md` para seleccionar una unidad con `Approval = APPROVED` cuyas dependencias previas `TT-*` estén en `DONE`.
2. **Especificación SDD (`resourcehub-sdd`):** Verificar o elaborar la ficha `SPEC-<MOD>-<NNN>` en sus 15 secciones y superar las puertas `QG-01` y `QG-02`.
3. **Aprobación humana de cambios o bloqueos:** Si concurre cualquiera de las 8 condiciones *Stop-the-Line* (`STL-01..08`), detenerse y solicitar decisión del propietario.
4. **Creación de rama desde `main`:**
   - `feat/TT-XXX-<slug>` para tareas técnicas e historias.
   - `spec/SPEC-YYY-NNN-<slug>` para especificaciones SDD.
   - `fix/<ID>-<slug>` para correcciones.
   - `chore/<alcance>-<slug>` para mantenimiento.
5. **Construcción TDD por capas XF (`resourcehub-tdd`):** Iterar `RED -> GREEN -> REFACTOR` en orden `repository` (`R`) -> `business` (`B`, solo ENUM `ErrorCode`) -> `api` (`A`) -> `COMP-GW`, respetando las **50 reglas XF** y las **8 reglas ResourceHub**.
6. **Validación local completa:** Ejecutar `./scripts/dev.sh verify` (Checkstyle, escaneo de secretos, tests unitarios/integración y cobertura JaCoCo $\ge 90\%$ en `business/logic`).
7. **Convención de Commits:**
   ```text
   <tipo>(<módulo-o-TT>): <descripción en imperativo> [TT-XXX] [SPEC-YYY-NNN]
   ```
8. **Pull Request con las 7 Evidencias (`QG-06`):** Completar la plantilla `.github/pull_request_template.md`.
9. **Ejecución de CI en GitHub Actions (`ci-pr.yml`):** Todas las puertas deben estar en verde.
10. **Aprobación humana e integración (*Squash and Merge*):** Tras el merge en `main`, eliminar la rama integrada y actualizar el estado en el backlog y en Google Drive.
