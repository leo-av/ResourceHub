## 1. Identificación de la Unidad de Trabajo, Flujo de Ramas y Trazabilidad

- **Tipo de PR y Flujo de Ramas (`DEC-099`):**
  - [ ] **Desarrollo / Cambio normal (`feat/*`, `fix/*`, `spec/*`, `chore/*`, `refactor/*`, `test/*`) → base `develop` (Preproducción)**
  - [ ] **Promoción a Producción (`develop` → `main`)** *(Exige CI/CD `ci-develop.yml` en verde en Preproducción)*
  - [ ] **Hotfix productivo (`hotfix/*` → `main`)** *(Exige backmerge obligatorio posterior hacia `develop`)*
- **Tarea Técnica (`TT-*`):** `TT-___`
- **Historia de Usuario (`HU-*`) / Funcionalidad (`FUN-*`):** `HU-___` / `FUN-___`
- **Entrega MVP:** `MVP-___`
- **Especificación SDD (`SPEC-*`):** `SPEC-___-___`
- **Issue vinculado:** `Closes #___`

---

## 2. Checklist de las 7 Evidencias Obligatorias de Cierre (`QG-06`)

> [!IMPORTANT]
> Conforme a `.agents/skills/resourcehub-development-workflow/SKILL.md` (§7), `DEC-099` y `ResourceHub — Backlog Técnico Implementable v1.0.md` (§7.2), ninguna unidad de trabajo puede integrarse en `develop` (Preproducción) ni promocionarse a `main` (Producción) sin completar estas 7 evidencias y respetar la política de ramas (`scripts/validate-branch-flow.sh`):

- [ ] **Evidencia 1 (`SPEC-*` SDD):** Ficha `SPEC-<MOD>-<NNN>` completa en sus 15 secciones con `QG-01` y `QG-02` en `PASS`.
- [ ] **Evidencia 2 (Ciclo TDD `RED -> GREEN -> REFACTOR`):** Pruebas escritas antes del código productivo, verificadas en `RED` (`QG-03`) y pasadas a `GREEN` sobre `Testcontainers PostgreSQL 16+` cuando toca persistencia.
- [ ] **Evidencia 3 (Triple Aserción):** Verificado (a) Contrato `ResponseWrapper<T>` / RFC 9457 interno + `X-Request-Id`, (b) Estado en PostgreSQL 16+ y (c) Histórico / Auditoría autónoma `REQUIRES_NEW` (`0 PII`) / Outbox.
- [ ] **Evidencia 4 (Doble Conformidad Arquitectónica `PRU-02`):** `0 violaciones` en las **8 Reglas ResourceHub** (`RNF-MANT-001`) + **50 Reglas XF** (`.agents/skills/xf-architecture/SKILL.md`).
- [ ] **Evidencia 5 (Cobertura y Escenarios):** 100% de los escenarios *Given / When / Then* de la `SPEC-*` cubiertos y cobertura de ramas **$\ge 90\%$** en `business/logic`.
- [ ] **Evidencia 6 (Seguridad, Flujo de Ramas y Catálogo `DTD-011`):** Superadas `scripts/validate-branch-flow.sh`, pruebas `PRU-SEG-*` aplicables, `./scripts/security-scan.sh` limpio (`0 secretos`) y sincronización 1:1 `ErrorCode <-> platform_catalog.error_catalog`.
- [ ] **Evidencia 7 (Sincronización de Estado Tridimensional):** Estado actualizado a `Approval: APPROVED | Definition: SPECIFIED | Execution: IN_REVIEW -> DONE`.
