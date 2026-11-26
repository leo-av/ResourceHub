# Paquetes de Lectura Contextual Acotada (8 Perfiles de `RH-GUI-AGE-v1.0`), Estados Tridimensionales y Quality Gates `QG-01..06`

Recurso normativo empaquetado de la SKILL [`resourcehub-development-workflow`](../SKILL.md), alineado 1:1 con `ResourceHub — Guía de Preparación para Desarrollo con Agentes v1.0 (RH-GUI-AGE-v1.0 §2–§4)` y `ResourceHub — Flujo de Desarrollo SDD y TDD v1.0 (RH-FLU-SDD-TDD-v1.0 §1)`.

---

## 1. Paquete Transversal Siempre Obligatorio (Núcleo de 5 Artefactos)

En **toda** tarea técnica `TT-001..106`, el agente debe consultar siempre este núcleo mínimo de 5 elementos:
1. Ficha de la `TT-*`, `HU-*` y `FUN-*` en `ResourceHub — Backlog Técnico Implementable v1.0.md` (`RH-BACK-TEC-IMP-v1.0`).
2. Entrada `SPEC-<MOD>-<NNN>` en `ResourceHub — Catálogo de Especificaciones de Implementación v1.0.md` (`RH-CAT-ESP-IMP-v1.0`).
3. `.agents/skills/xf-architecture/SKILL.md` (50 reglas del patrón XF, `DTD-003`).
4. `.agents/skills/response-wrapper/SKILL.md` (contrato `ResponseWrapper<T>` + `ErrorDetail` + `X-Request-Id`, `DTD-007`).
5. `ResourceHub — Flujo de Desarrollo SDD y TDD v1.0.md` (`RH-FLU-SDD-TDD-v1.0`, o las SKILLs operativas `resourcehub-sdd` y `resourcehub-tdd`).

---

## 2. Los 8 Paquetes de Lectura Contextual Acotada por Módulo (Cero Lectura Indiscriminada, `RH-GUI-AGE-v1.0 §3`)

Además del Núcleo Transversal (1–5), el agente cargará **únicamente** las secciones específicas de la fila correspondiente al módulo de su `TT-*`:

| # | Módulo / Componente y Tareas (`TT-*`) | Secciones Específicas Adicionales que DEBE Leer el Agente | Documentos que NO Necesita Cargar |
| :-: | :--- | :--- | :--- |
| **1** | **`MOD-11` (`núcleo compartido`) y `COMP-GW` (API Gateway)**<br>`TT-001..004`, `TT-026`, `TT-035`, `TT-084` | • `RH-DIS-TEC-v1.0` (§2.2 responsabilidades `COMP-GW`, §3 XF, §5 catálogo híbrido `FFMMCC`, §6.2 puertas CI)<br>• `RH-ESP-INT-CON-v1.0` (§1, §2 taxonomía de hosts y rutas, §3 tabla de 34 códigos `FFMMCC`, §6 ejemplos JSON)<br>• `RH-DIS-TEC-SEG-v1.0` (§1 Perímetro 1, §2 `PRU-SEG-01..05`, `PRU-SEG-10`)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.34 `platform_catalog.error_catalog`). | Detalles de series recurrentes, calendarios externos, saga de pagos o políticas. |
| **2** | **`MOD-01 plataforma`** (esquemas `platform` y `platform_catalog`)<br>`TT-003`, `TT-032..034`, `TT-074`, `TT-089`, `TT-091` | • `Especificación Funcional v1.0` (`RF-ORG-001..004`) y `No Funcional v1.0` (`RNF-TEN-001..003`, `RNF-ESC-002`, `RNF-REC-001..002`)<br>• `Catálogo de Casos de Uso v1.0` (`CU-ORG-001`) y `Reglas de Negocio v1.0` (`RN-ORG-001`, `RN-TRA-005`)<br>• `RH-PROP-ARQ-v1.1` (`ADR-002`, `ADR-004`, `ADR-016`)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.1..4.5 y §4.34)<br>• `RH-ESP-INT-CON-v1.0` (§4 `PlatformB`, §5.1 host `platform.*`, §5.4 `/organization/settings`). | Módulos de disponibilidad, políticas, integraciones o pagos. |
| **3** | **`MOD-02 acceso` (*PII Vault*, esquema `access`)**<br>`TT-005..006`, `TT-016..017`, `TT-024..025`, `TT-027`, `TT-069`, `TT-094..096` | • `RF-ACC-001..005`, `RNF-SEG-001..004`, `RNF-PRIV-001`, `RNF-PRIV-003`<br>• `CU-ACC-001..002`, `RN-ACC-001..004`, `RN-TRA-004..005`<br>• `RH-PROP-ARQ-v1.1` (`ADR-011`, `ADR-015` *PII Vault*, `ADR-017`)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.6..4.10 esquema `access`)<br>• `RH-ESP-INT-CON-v1.0` (§4 `AccessB`, §5.2..5.5 `/auth/**`, `/users`, `/gdpr-erasure`, `/api-clients`)<br>• `RH-REG-DEC-TEC-v1.0` (`DTD-001`, `DTD-004`, `DTD-005`). | Algoritmos GiST, series DST o tarifas/contabilidad. |
| **4** | **`MOD-03 recursos` y `MOD-04 disponibilidad`** (esquemas `resources` y `availability`)<br>`TT-007..010`, `TT-015`, `TT-023`, `TT-044`, `TT-046` | • `RF-CAT-001..004`, `RF-DIS-001..004`, `RNF-REL-004`<br>• `CU-CAT-001`, `CU-DIS-001..002`, `RN-CAT-001..004`, `RN-DIS-001..002`<br>• `Maestro v1.5` (§7.2, §7.4 orden `D-5`, `D-6`, `D-10`, `D-15`)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.11..4.15 esquemas `resources` y `availability`, `MDD-002`, `MDD-005..006`, `MDD-008`)<br>• `RH-ESP-INT-CON-v1.0` (§4 `CatalogB`, `AvailabilityB`, §5.2 y §5.4). | SSO OIDC, Kafka, pagos o baja RGPD. |
| **5** | **`MOD-05 reservas` (Core, `D-44`, Flujos, esquema `reservations`)**<br>`TT-011..012`, `TT-014`, `TT-018..019`, `TT-028..030`, `TT-045`, `TT-047..049`, `TT-055..060` | • `RF-RES-001..007`, `RF-FLU-001..003`, `RF-DIS-003..004`, `RNF-REL-001..002`, `RNF-REL-004`<br>• `CU-RES-001..007`, `CU-FLU-001..003`, `RN-RES-001..012`, `RN-FLU-001..003`<br>• `RH-PROP-ARQ-v1.1` (`ADR-005`, `ADR-008`, `ADR-013`, `MOD-ARQ-01`, `MOD-ARQ-09`)<br>• `RH-DIS-TEC-v1.0` (§6 Algoritmos 1 y 2)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.16..4.20 esquema `reservations`, `MDD-007`, `MDD-009`)<br>• `RH-ESP-INT-CON-v1.0` (§4, §5.2..5.4 `/reservations`, `/blocks/preview`, `/sessions/cancel`, `/recurring-series`, `/waitlist`). | Infraestructura K8s/Cloud o detalles internos de OIDC. |
| **6** | **`MOD-06 políticas`, `MOD-07 trazabilidad` y `MOD-08 eventos`** (`policies`, `traceability`, `events`)<br>`TT-020..022`, `TT-052..054`, `TT-065..068`, `TT-075`, `TT-077`, `TT-082` | • `RF-POL-001..002`, `RF-TRA-001..005`, `RNF-OBS-001..003`, `RNF-DISP-001..002`, `RNF-INT-002`<br>• `CU-POL-001..002`, `CU-TRA-001..003`, `RN-POL-001`, `RN-TRA-002..004`<br>• `RH-PROP-ARQ-v1.1` (`ADR-009`, `ADR-012`, `DEC-073`)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.21..4.27 esquemas `policies`, `traceability`, `events`, `MDD-010..011`, `MDD-015`)<br>• `RH-REG-DEC-TEC-v1.0` (`DTD-006`, `DTD-007`). | Vistas Frontend o conectores de calendario/pasarela. |
| **7** | **`MOD-09 integraciones` y `MOD-10 pagos`** (esquemas `integrations` y `payments`)<br>`TT-092..093`, `TT-097`, `TT-098..101` | • `RF-INT-001..003`, `RF-PAG-001..003`, `RNF-REL-002..003`, `RNF-PRIV-001..002`, `RNF-SEG-004`<br>• `CU-INT-001..003`, `CU-PAG-001`, `RN-INT-001`, `RN-PAG-001`<br>• `RH-PROP-ARQ-v1.1` (`ADR-017`, `MOD-ARQ-04`, `MOD-ARQ-08..09`, `INC-011`)<br>• `RH-DIS-TEC-v1.0` (§6 Algoritmo 3)<br>• `RH-MOD-FIS-DAT-v1.1` (§4.28..4.33 esquemas `integrations` y `payments`, `MDD-001`, `MDD-012`, `MDD-014`)<br>• `RH-REG-DEC-TEC-v1.0` (`DTD-008`). | Árboles `ltree` o aprovisionamiento de BDs. |
| **8** | **`EXP-01 WebCliente` y `EXP-02 BackOffice` (SPAs XF)**<br>`TT-036..043`, `TT-050..051`, `TT-061..063`, `TT-070..072`, `TT-079..080`, `TT-083`, `TT-102..105` | • `RF-EXP-001..002`, `RNF-ACC-001`, `RNF-REL-003` (`INC-011`), `CU-EXP-001`, `RN-ACC-004`<br>• `Maestro v1.5` (§4.2..4.4, `D-33`, `D-38`, `D-42..44`)<br>• `RH-CAT-ESP-IMP-v1.0` (§3 tabla de revisión `SPEC-EXP-001/002` del MVP activo)<br>• `RH-ESP-INT-CON-v1.0` (§5 endpoints exactos del MVP anterior que consume la vista). | SQL físico, triggers PostgreSQL, HikariCP o Kafka. |

---

## 3. Modelo de Estados Tridimensionales del Backlog Técnico Implementable

Cada elemento (`FUN-*`, `HU-*`, `TT-*`) se gobierna mediante tres ejes de estado independientes:

| Eje de Estado | Valores Válidos | Regla Operativa en `resourcehub-development-workflow` |
| :--- | :--- | :--- |
| **1. `Approval` (Aprobación)** | `DRAFT` \| `READY_FOR_REVIEW` \| `APPROVED` | **Bloqueo duro de entrada:** Prohibido iniciar implementación (`IN_PROGRESS`) si `Approval != APPROVED`. Solo el propietario humano puede promover un elemento a `APPROVED`. |
| **2. `Definition` (Definición SDD)** | `NEEDS_CLARIFICATION` \| `SPECIFIED` | Un elemento con Entry Gate `OPEN-*` (`MRD-007`) inicia en `NEEDS_CLARIFICATION`. Pasa a `SPECIFIED` tras verificar la decisión del propietario o documentar en la Sección 15 de la `SPEC-*` el valor por defecto aprobado en `MRD-007` y superar `QG-01` y `QG-02`. |
| **3. `Execution` (Ejecución TDD)** | `BLOCKED_BY_DEPENDENCY` → `READY_FOR_DEVELOPMENT` → `IN_PROGRESS` → `DONE` | • `BLOCKED_BY_DEPENDENCY`: Tiene al menos una `TT-*` predecesora en el DAG que no está en `DONE`.<br>• `READY_FOR_DEVELOPMENT`: `Approval == APPROVED`, `Definition == SPECIFIED` (`QG-01..02` PASS) y 100% dependencias previas en `DONE`.<br>• `IN_PROGRESS`: En Fase 4/5/6 (`resourcehub-tdd`).<br>• `DONE`: Superados `QG-03`, `QG-04`, `QG-05` y `QG-06` con entrega de las 7 evidencias. |

---

## 4. Tabla de Control de las 6 Puertas de Calidad (`QG-01` a `QG-06`, `RH-FLU-SDD-TDD-v1.0 §1`)

| Puerta | Fase | SKILL Ejecutora | Pregunta de Control / Criterio de Superación | Acción si NO cumple (`FAIL`) |
| :---: | :---: | :--- | :--- | :--- |
| **`QG-01`** | Fase 3 (SDD) | `resourcehub-sdd` | ¿Existe algún bloqueo `BLOCKER`/`HIGH` o falta confirmar/aplicar el Entry Gate `MRD-007` (`OPEN-*`)? | Activar *Stop-the-Line* y escalar a decisión humana antes de redactar/cerrar la `SPEC-*`. |
| **`QG-02`** | Fase 3 (SDD) | `resourcehub-sdd` | ¿La `SPEC-<MOD>-<NNN>` tiene sus 15 secciones y cumple XF (50 reglas), DAG, *PII Vault*, `ResponseWrapper<T>`, `ErrorCode -> FFMMCC` y el 100% de `RN`/`CU`? | Completar o corregir la `SPEC-<MOD>-<NNN>` en `resourcehub-sdd`; prohibido pasar a TDD. |
| **`QG-03`** | Fase 5 (TDD) | `resourcehub-tdd` | ¿El test escrito primero en fase `RED` falla exactamente por la aserción funcional esperada (y no por un error de configuración o entorno)? | Reparar el entorno, contenedor o compilación antes de escribir código productivo (*falso RED* prohibido). |
| **`QG-04`** | Fase 5 (TDD) | `resourcehub-tdd` | ¿Se han completado todos los escenarios *Given / When / Then* de la `SPEC-*` con cobertura `≥ 90%` en `business/logic` y `0 violaciones` en `PRU-02` (8 reglas RH + 50 reglas XF)? | Iterar sobre el siguiente escenario en `resourcehub-tdd` o corregir violaciones arquitectónicas en `REFACTOR`. |
| **`QG-05`** | Fase 6 (CI) | `resourcehub-tdd` | ¿Están al 100% en verde las **6 Puertas de CI** (Tipado + `PRU-02` + `PRU-01` + `Testcontainers PG 16+` con sync `ErrorCode <-> error_catalog`, GiST, *PII Vault* + `ResponseWrapper` y Gateway) y la **Triple Aserción**? | Regresar a Fase 5 (`resourcehub-tdd`) para corregir la implementación, o a Fase 3 (`resourcehub-sdd`) si requiere ajuste *Spec-First*. |
| **`QG-06`** | Fase 8 (Cierre) | `resourcehub-development-workflow` | ¿Se han generado las **7 evidencias obligatorias**, sincronizado la matriz técnica y validado el cierre de la unidad / hito? | Completar evidencias y matrices antes de marcar `DONE`; al cierre de un hito `HITO-01..08`, solicitar aprobación humana. |
