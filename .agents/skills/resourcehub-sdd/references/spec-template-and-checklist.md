# Plantilla Oficial `SPEC-<MOD>-<NNN>` (15 Secciones de `RH-EST-SDD-v1.0`) y Checklists `QG-01` / `QG-02`

Recurso normativo empaquetado de la SKILL [`resourcehub-sdd`](../SKILL.md). Contiene la estructura exacta de 15 secciones aprobada en `ResourceHub — Estándar de Desarrollo SDD v1.0 (§2)` y la lista de comprobación verificable de los Quality Gates `QG-01` y `QG-02`.

---

## 1. Plantilla Normalizada `SPEC-<MOD>-<NNN>` (15 Secciones Obligatorias)

```markdown
# `SPEC-<MOD>-<NNN>` — <Nombre Conciso de la Especificación>

## 1. Identificador y Nombre
- **Código:** `SPEC-<MOD>-<NNN>`
- **Nombre:** `<Nombre conciso y descriptivo>`

## 2. Versión, Estado y MVP
- **Versión:** `v1.0`
- **Estado SDD:** `DRAFT` | `UNDER REVIEW` | `APPROVED` | `IMPLEMENTED` | `SUPERSEDED`
- **Entrega / Hito:** `MVP-0`..`MVP-14` (`HITO-01`..`HITO-08`)
- **Unidades del Backlog Técnico Implementable:** `INI-*` → `EP-*` → `FUN-*` → `HU-*` → `TT-*`

## 3. Objetivo y Contexto
- Descripción precisa del problema funcional/técnico que resuelve dentro del módulo y su relación con las historias `HU-*` y tareas `TT-*` asignadas.

## 4. Alcance Incluido y Exclusiones
- **Alcance incluido en este MVP:** Lista cerrada de comportamientos a implementar.
- **Exclusiones explícitas:** Qué se difiere a entregas posteriores (indicando `MVP-N` destino y aplicando el decalaje `+1 MVP` de Frontend según `D-38` cuando corresponda).

## 5. Trazabilidad Normativa
| Categoría | Identificadores Trazados |
| :--- | :--- |
| **Requisitos Funcionales y No Funcionales** | `RF-*`, `RNF-*` |
| **Procesos, Casos de Uso y Reglas de Negocio** | `PF-*`, `CU-*`, `RN-*`, `CIC-*` |
| **Criterios de Aceptación Maestros** | `AC-001..023` |
| **Directrices y Decisiones Aprobadas** | `D-*`, `ADR-*`, `MDD-*`, `MRD-*`, `DTD-*` |

## 6. Mapa de Componentes XF Implicados (`DTD-003` y `skills/xf-architecture/SKILL.md`)
- **Módulo propietario:** `MOD-01..11` / `COMP-GW` / `EXP-01..02`
- **3 Capas Cerradas y 5 Tipos Cerrados XF:**
  - **Capa `repository` (fachada `R`):** `repository/logic/*Repository`, `repository/base/*`, `repository/utils/*Utils`
  - **Capa `business` (fachada `B`):** `business/logic/*Business`, `business/base/*`, `business/utils/*Utils`
  - **Capa `api` (fachada `A`):** `api/logic/services/*Service` (Backend REST) o `api/logic/gui/*View` (Frontend SPA), `api/base/*`, `api/utils/*Utils`
  - **Paquete `structs/`:** Estructuras de datos puras **sin sufijos `DTO`, `Entity` ni `Model`**, más excepciones tipadas `*Exception` portadoras de `ErrorCode`.
  - **Ciclo de vida:** Inicialización/terminación vía `XF.init()` / `XF.terminate()`.

## 7. Contratos Afectados (`DTD-001`, `DTD-007` y `skills/response-wrapper/SKILL.md`)
- **Operación interna en fachada `B`:** `B.<negocio>.<operacion>(...)`
- **Host y Canal Exterior en `COMP-GW` (`DTD-001`):**
  - Host Plataforma: `https://platform.resourcehub.app/api/v1/**` (solo `ACT-PA`), o
  - Host Organización: `https://{orgSlug}.resourcehub.app/api/v1/{app|workspace|console|external|webhooks/inbound}/**` (con `/auth/**` integrado en cada canal).
- **Tipo de retorno encapsulado en `ResponseWrapper<T>` (`DTD-007`):**
  - Estructura exterior cerrada: `{ code, requestId, timestamp, description?, data?, errors? }` + `ErrorDetail { field?, message }` + cabecera `X-Request-Id`.
  - En listados paginados: `T` es `PageResponse<Item>` (`/api/v1/...`) o `CursorPageResponse<Item>` (`/api/v2/...` desde `MVP-7`) alojado dentro de `data`.

## 8. Entradas y Validaciones Estructurales
- Estructuras de entrada en `structs/` y reglas de validación sintáctica/estructural.
- Cualquier fallo de validación sintáctica emite `ErrorCode.VALIDATION_FAILED` (traducido por `COMP-GW` a `code = 100001`, `HTTP 400`, rellenando `errors[].field` y `errors[].message` sin `data` ni `description`).

## 9. Seguridad en Gateway y Dominio
- **Validación perimetral en `COMP-GW`:** Verificación de host (`platform.*` vs `{orgSlug}.*`) y correspondencia entre el Ámbito Operacional del token (`PLATFORM`, `AMB-01` `/app`, `AMB-02` `/workspace`, `AMB-03` `/console`, `EXTERNAL_CLIENT` `/external`) y el prefijo de ruta (`401`/`403` `200003` si no procede).
- **Autorización en Dominio:** Roles permitidos (`ACT-PA`, `ACT-AO`, `ACT-AR`, `ACT-OP`, `ACT-US`), validación `holder == requester` en canal `/app`, y propagación de `OrganizationContext` inmutable (`D-42`, `D-43`).

## 10. Comportamiento Esperado y Flujos
- Secuencia principal paso a paso en `business/logic/*Business` y flujos alternativos/excepcionales.
- Si aplica a reservas/disponibilidad/pagos: algoritmo de ordenación canónica de recursos (`ADR-008`), expiración síncrona en línea de `PENDING` vencidas (`MOD-ARQ-09`), previsualización de bloqueos con confirmación forzada (`D-44`), o compensación automática de reembolso 100%.

## 11. Persistencia, Transaccionalidad y Concurrencia
- **Base de datos y tablas (`Modelo Físico de Datos v1.1`):** `resourcehub_platform_db` (`platform.*`, `platform_catalog.error_catalog`) o `resourcehub_org_<slug>_db` (`access.*`, `resources.*`, `availability.*`, `reservations.*`, `policies.*`, `traceability.*`, `events.*`, `integrations.*`, `payments.*`).
- **Demarcación transaccional:** `REQUIRED` para la operación de dominio + `REQUIRES_NEW` obligatorio para `traceability.audit_logs` (`ADR-012`).
- **Mecanismos de concurrencia y privacidad:** Bloqueo pesimista `SELECT ... FOR UPDATE` en orden canónico, constraint `EXCLUDE USING gist`, árboles `ltree`, y confinamiento absoluto de datos personales en `MOD-02 acceso` (*PII Vault*, `ADR-015`).

## 12. Salidas, Eventos y Trazabilidad
- **Respuesta exterior:** `ResponseWrapper<T>` con `code = 0` en éxito y `X-Request-Id` correlacionado.
- **Histórico de estados:** Inserción en `reservations.reservation_history` (cuando afecte a reservas).
- **Auditoría inmutable:** Asiento en `traceability.audit_logs` (`REQUIRES_NEW`) registrando `ErrorCode`, `requestId` y `0 PII`.
- **Eventos de dominio (Outbox):** Inserción atómica en `events.outbox_events` (`SKIP LOCKED`) incluyendo `schemaVersion` y garantizando **`0 PII`** en el payload.

## 13. Catálogo de Errores Esperados (`DTD-002`, `DTD-011`)
| `ErrorCode` ENUM (emitido por módulo en RFC 9457 interno) | `numeric_value` `FFMMCC` (`platform_catalog.error_catalog` en `COMP-GW`) | HTTP Status | Condición de Disparo |
| :--- | :---: | :---: | :--- |
| `ErrorCode.VALIDATION_FAILED` | `100001` | `400` | Error de validación estructural en campos de entrada |
| ... | ... | ... | ... |

## 14. Criterios de Aceptación y Escenarios TDD (*Given / When / Then*)
| ID Escenario | Escenario (*Given / When / Then*) | Nivel de Prueba (`PRU-01..11`) | Aserción Triple Esperada |
| :--- | :--- | :---: | :--- |
| `ESC-01` | **Given** [...] **When** [...] **Then** [...] | `PRU-01`, `PRU-03`, `PRU-04`, `PRU-05` | `ResponseWrapper` + Estado PostgreSQL 16+ + `audit_logs`/Outbox |
| ... | ... | ... | ... |

## 15. Dependencias y Decisiones Pendientes
- **Especificaciones y tareas previas requeridas:** `SPEC-*`, `TT-*`.
- **Cuestiones `OPEN-*` aplicables (`MRD-007`):** Confirmación del propietario o declaración explícita del valor por defecto aprobado en `MRD-007`. (`0` bloqueos `BLOCKER`/`HIGH` abiertos).
```

---

## 2. Checklists de Verificación para Quality Gates SDD (`QG-01` y `QG-02`)

### 2.1. Checklist `QG-01` (Control de Bloqueos y Entry Gates)

- [ ] **1. Estado de aprobación:** La unidad de trabajo (`FUN-*`/`HU-*`/`TT-*`) tiene `Approval = APPROVED` en `Backlog Técnico Implementable v1.0.md`.
- [ ] **2. Dependencias previas:** Todas las `TT-*` predecesoras en la `Matriz de Dependencias del Backlog v1.0.md` están completadas (o especificadas si se trata solo de fase SDD).
- [ ] **3. Ausencia de bloqueos críticos:** No existe ninguna cuestión abierta con severidad `BLOCKER` o `HIGH` sobre la especificación.
- [ ] **4. Entry Gates `MRD-007`:** Si la tarea está vinculada a una cuestión `OPEN-*` de Nivel 2, se ha verificado la decisión del propietario o se ha documentado en la Sección 15 el valor por defecto aprobado en `MRD-007`.

### 2.2. Checklist `QG-02` (Completitud de 15 Secciones, XF, Gateway, `ResponseWrapper` y *PII Vault*)

- [ ] **1. Completitud de las 15 secciones:** Las Secciones 1 a 15 de `SPEC-<MOD>-<NNN>` están íntegramente redactadas, sin `TODO`, `TBD` ni ambigüedades.
- [ ] **2. Conformidad Arquitectónica XF (`DTD-003`):** La Sección 6 mapea los componentes exclusivamente en las 3 capas cerradas (`api`, `business`, `repository`) y 5 tipos cerrados (`logic/`, `base/`, `R`/`B`/`A`/`XF`, `utils/`, `structs/` sin sufijos `DTO`/`Entity`/`Model`).
- [ ] **3. Conformidad con las 8 Reglas ResourceHub (`RNF-MANT-001`):** Respeta el DAG acíclico de módulos, `0` FKs inter-módulo (`ADR-007`), `OrganizationContext` inmutable, `0 PII` fuera del esquema `access` (`ADR-015`), auditoría en `REQUIRES_NEW` (`ADR-012`) y `0` tipos `float`/`double` para importes monetarios (`MoneyMinor`).
- [ ] **4. Separación de Hosts y Canales (`DTD-001`):** La Sección 7 utiliza `https://platform.resourcehub.app/api/v1/**` para plataforma o `https://{orgSlug}.resourcehub.app/api/v1/{app|workspace|console|external|webhooks/inbound}/**` con `/auth` integrado por canal.
- [ ] **5. Contrato `ResponseWrapper<T>` (`DTD-007`):** Todas las salidas exteriores especifican `ResponseWrapper<T>` (`{ code, requestId, timestamp, description?, data?, errors? }` + `X-Request-Id`), alojando `PageResponse<T>` o `CursorPageResponse<T>` dentro de `data`.
- [ ] **6. Catálogo Híbrido de Errores (`DTD-002`, `DTD-011`):** La Sección 13 garantiza que el módulo emite **únicamente** el ENUM simbólico `ErrorCode` (`MOD-11`) en RFC 9457 interno, y que cada `ErrorCode` tiene su correspondencia `numeric_value` (`FFMMCC`) en `platform_catalog.error_catalog` para su traducción en `COMP-GW`.
- [ ] **7. Escenarios TDD listos para fase `RED`:** La Sección 14 define escenarios *Given / When / Then* verificables que cubren el 100% de las reglas `RN-*` y casos de uso `CU-*` de la tarea, mapeados a `PRU-01..11`.
