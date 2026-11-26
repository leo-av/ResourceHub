## 1. Identificación de la Unidad de Trabajo y Trazabilidad

- **Tarea Técnica (`TT-*`):** `TT-___`
- **Historia de Usuario (`HU-*`) / Funcionalidad (`FUN-*`):** `HU-___` / `FUN-___`
- **Entrega MVP:** `MVP-___`
- **Especificación SDD (`SPEC-*`):** `SPEC-___-___`
- **Issue vinculado:** `Closes #___`

---

## 2. Checklist de las 7 Evidencias Obligatorias de Cierre (`QG-06`)

> [!IMPORTANT]
> Conforme a `.agents/skills/resourcehub-development-workflow/SKILL.md` (§7) y `ResourceHub — Backlog Técnico Implementable v1.0.md` (§7.2), ninguna unidad de trabajo puede integrarse en `main` ni marcarse como `DONE` sin completar estas 7 evidencias:

- [ ] **Evidencia 1 (`SPEC-*` SDD):** Ficha `SPEC-<MOD>-<NNN>` completa en sus 15 secciones con `QG-01` y `QG-02` en `PASS`.
- [ ] **Evidencia 2 (Ciclo TDD `RED -> GREEN -> REFACTOR`):** Pruebas escritas antes del código productivo, verificadas en `RED` (`QG-03`) y pasadas a `GREEN` sobre `Testcontainers PostgreSQL 16+` cuando toca persistencia.
- [ ] **Evidencia 3 (Triple Aserción):** Verificado (a) Contrato `ResponseWrapper<T>` / RFC 9457 interno + `X-Request-Id`, (b) Estado en PostgreSQL 16+ y (c) Histórico / Auditoría autónoma `REQUIRES_NEW` (`0 PII`) / Outbox.
- [ ] **Evidencia 4 (Doble Conformidad Arquitectónica `PRU-02`):** `0 violaciones` en las **8 Reglas ResourceHub** (`RNF-MANT-001`) + **50 Reglas XF** (`.agents/skills/xf-architecture/SKILL.md`).
- [ ] **Evidencia 5 (Cobertura y Escenarios):** 100% de los escenarios *Given / When / Then* de la `SPEC-*` cubiertos y cobertura de ramas **$\ge 90\%$** en `business/logic`.
- [ ] **Evidencia 6 (Seguridad y Catálogo de Errores `DTD-011`):** Superadas las pruebas `PRU-SEG-*` aplicables, `./scripts/security-scan.sh` limpio (`0 secretos`) y sincronización 1:1 `ErrorCode <-> platform_catalog.error_catalog`.
- [ ] **Evidencia 7 (Sincronización de Estado Tridimensional):** Estado actualizado a `Approval: APPROVED | Definition: SPECIFIED | Execution: IN_REVIEW -> DONE`.
