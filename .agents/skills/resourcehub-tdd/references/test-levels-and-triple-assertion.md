# Matriz Operativa de los 11 Niveles de Prueba (`PRU-01..11`), Triple Aserción y Suite `PRU-SEG-01..10`

Recurso normativo empaquetado de la SKILL [`resourcehub-tdd`](../SKILL.md), alineado 1:1 con `ResourceHub — Estrategia de Pruebas v1.0 (RH-EST-PRU-v1.0)` y `ResourceHub — Estándar de Desarrollo TDD v1.0 (RH-EST-TDD-v1.0)`.

---

## 1. Matriz de Selección y Verificación de los 11 Niveles de Prueba (`PRU-01` a `PRU-11`)

| ID | Nivel / Tipo de Prueba | Alcance y Componentes XF | Qué Verifica Obligatoriamente en `v1.0` | Puerta CI / Cuándo Ejecutar |
| :---: | :--- | :--- | :--- | :--- |
| **`PRU-01`** | **Unitarias de Negocio (`business`)** | `business/logic/*Business`, `business/utils/*Utils` y primitivas `MOD-11` (`TimeInterval`, `MoneyMinor`, `ErrorCode`). | El 100% de las **`28 RN`** (casos positivos, borde y negativos), fusión de medianoche (`MDD-008`), expansión DST (`ADR-013`), 5 niveles de `D-5`, motor de 6 políticas y lanzamiento del `ErrorCode` ENUM simbólico exacto (**0 mocks**, `Clock` inyectable, **0 valores numéricos `FFMMCC`**). | En cada ciclo local TDD + **Puerta 3 de CI** (`≥ 90%` cobertura de ramas en `business/logic`). |
| **`PRU-02`** | **Conformidad Arquitectónica (ResourceHub + XF)** | Todos los paquetes de `MOD-01..11`, `COMP-GW` y `EXP-01..02`. | **8 Reglas ResourceHub (`RNF-MANT-001`)** (`business` sin framework, DAG acíclico, 0 FKs inter-módulo `ADR-007`, `OrganizationContext` obligatorio, 0 PII fuera de `access` `ADR-015`, auditoría `REQUIRES_NEW` `ADR-012`, 0 `float`/`double` monetarios, `Clock` explícito) **+ 50 Reglas XF (`DTD-003`)** (3 capas cerradas `api`/`business`/`repository`, 5 tipos cerrados, `structs/` sin sufijos `DTO`/`Entity`/`Model`, fachadas `R`/`B`/`A`/`XF`, aislamiento de `base` y `utils`). | **Puerta 2 de CI** obligatoria (`0 violaciones`) en cada fase `REFACTOR` desde `MVP-0`. |
| **`PRU-03`** | **Integración y Persistencia Relacional** | `repository/logic/*Repository` + `business/logic/*Business` contra **PostgreSQL 16+ (`Testcontainers`)**. | Migraciones Flyway `platform` y `org`, **sincronización bidireccional 1:1 entre todos los valores del ENUM `ErrorCode` (`MOD-11`) y las filas activas de `platform_catalog.error_catalog` (`DTD-011`)**, `EXCLUDE USING gist`, `ltree`, triggers *append-only*, `REQUIRES_NEW` y Outbox `SKIP LOCKED`. | **Puerta 4 de CI** en cada commit desde `MVP-0`. |
| **`PRU-04`** | **Contratos Internos, API Gateway y `ResponseWrapper`** | `api/logic/services/*Service` + **`COMP-GW` (API Gateway)**. | Los **9 puntos de verificación de `skills/response-wrapper/SKILL.md`**: (1) `code` entero `FFMMCC`, `requestId == X-Request-Id`, `timestamp` ISO-8601 UTC; (2) OK con `data` (`PageResponse<T>` / `CursorPageResponse<T>`) sin `errors`; (3) OK sin `data` ni `errors`; (4) KO de validación (`100001`) con `errors[].field` sin `description`/`data`; (5) KO de negocio traducido desde RFC 9457 interno sin detalles técnicos; (6) ruta inexistente `404` (`300001`); (7) error no controlado `500` (`900001`) con fallback estático y `requestId` en log; (8) regeneración de `X-Request-Id` inseguro (`>128 chars` o caracteres inválidos); (9) 0 códigos numéricos mágicos en handlers. | **Puerta 5 de CI** en cada commit desde `MVP-0`. |
| **`PRU-05`** | **Funcionales de Casos de Uso** | Monolito XF completo + `COMP-GW` en `Testcontainers PostgreSQL 16+`. | Los **`26 CU`** y **`12 PF`** con **Triple Aserción obligatoria** (ver Sección 2 de este documento). | **Puerta 4/5 de CI** progresivamente por MVP. |
| **`PRU-06`** | **Aceptación (*Given / When / Then*)** | `COMP-GW` + Monolito + SPAs XF `WebCliente` (`EXP-01`) y `BackOffice` (`EXP-02`, Playwright). | Los **`23 AC`** y **`74 escenarios`** del Catálogo de Casos de Uso (por API en `MVP-N` y en navegador E2E en `MVP-(N+1)` según `D-38`). | En cada entrega MVP (API desde `MVP-0`, UI E2E desde `MVP-3.2`). |
| **`PRU-07`** | **Seguridad, Ámbitos en Gateway y RGPD** | **`COMP-GW`** + `MOD-02 acceso` (*PII Vault*) + todos los canales. | Batería **`PRU-SEG-01` a `PRU-SEG-10`** (ver Sección 3 de este documento). | **Puerta 5 de CI** desde `MVP-1/2`. |
| **`PRU-08`** | **Aislamiento Multi-Tenant** | `COMP-GW` + `TenantConnectionRouter` + 2 BDs físicas (`org-alfa`, `org-beta`). | `0` fugas cross-tenant en consultas, escrituras, caché Redis (`rh:org:{orgId}:*`), Kafka, jobs y auditoría; respuesta opaca `404` (`300001`); bloqueo en `SUSPENDED`/`ARCHIVED`; desalojo LRU de pools (`AC-002`). | CI desde `MVP-0` (multi-BD completa desde `MVP-3.1`). |
| **`PRU-09`** | **Concurrencia y Anti-Deadlocks** | `MOD-05 reservas` + PostgreSQL 16+ multihilo. | `N` hilos simultáneos sobre `capacity = 1` (`AC-004`), `capacity = K`, reservas compuestas cruzadas con orden canónico (`AC-012`), padre-hijo `ltree` (`AC-013`) y liberación síncrona en línea de `PENDING` vencidas (`AC-014`). | CI desde `MVP-1` (ampliada en `MVP-2/4/5`). |
| **`PRU-10`** | **Resiliencia de Integraciones y Compensación** | `COMP-GW` + `MOD-08/09/10` + simuladores `WireMock`. | Caída de `platform_db` (fallback estático en Gateway), caída de receptor webhook (`QUARANTINED`, `ADR-017`), colisión de calendario externo (`AC-019`) y **reembolso compensatorio automático del 100% ante cobro tardío sobre reserva `EXPIRED` (`MOD-ARQ-09`)**. | CI desde `MVP-5`, `MVP-9`, `MVP-12`, `MVP-13`. |
| **`PRU-11`** | **Rendimiento, Benchmarks y Regresión** | Plataforma completa multi-tenant + Redis (`MVP-7`) + Kafka (`MVP-8`). | Benchmarks sobre particiones mensuales (`MDD-015`), paginación `/api/v2` por cursor, verificación de que `MOD-05 reservas` no usa Redis para conflictos (`RNF-PERF-003`) y `0` regresiones en toda la suite acumulada. | Regresión en cada PR; benchmark desde `MVP-7`. |

---

## 2. Checklist de la Triple Aserción (Obligatoria en `PRU-03`, `PRU-05`, `PRU-06`, `PRU-07`, `PRU-08`, `PRU-09`, `PRU-10`)

Toda prueba que ejecute un caso de uso o mutación de estado (tanto en escenarios de éxito como de fallo) **debe incluir explícitamente las 3 bloques de aserción siguientes**:

```markdown
### Aserción 1: Contrato de Respuesta (Doble Borde: RFC 9457 Interno / `ResponseWrapper<T>` Exterior)
- [ ] **En test interno de módulo (`*Business` / `*Service`):**
  - En éxito: devuelve la estructura pura de `structs/` esperada.
  - En fallo: lanza `*Exception` / `application/problem+json` (RFC 9457) con el `ErrorCode` ENUM simbólico exacto de `MOD-11` (sin número `FFMMCC`).
- [ ] **En test a través de `COMP-GW` (API Gateway):**
  - En éxito: `HTTP 200/201`, `code == 0`, `requestId == header("X-Request-Id")`, `timestamp` UTC válido, `data` poblado (o `description` en operaciones sin payload) y `errors == null`.
  - En fallo: `HTTP 4XX/5XX`, `code == <FFMMCC>` (entero de 6 dígitos traducido desde `platform_catalog.error_catalog`), `requestId == header("X-Request-Id")`, `errors` poblado con `ErrorDetail { field?, message }`, `data == null`, `description == null`, y **0 fugas** de `application/problem+json`, trazas de pila o nombres SQL.

### Aserción 2: Estado Exacto en PostgreSQL 16+ (`Testcontainers`)
- [ ] **En operación exitosa:** Consulta directa a `<esquema>.<tabla>` confirma la persistencia exacta de los campos, estados (`CIC-01..06`), importes en unidades mínimas enteras (`MoneyMinor`) y marcas temporales UTC.
- [ ] **En operación rechazada (validación, conflicto GiST, política, seguridad):** Consulta directa a `<esquema>.<tabla>` confirma que la transacción principal hizo **rollback íntegro** y el estado previo permanece intacto.

### Aserción 3: Trazabilidad, Auditoría (`REQUIRES_NEW`) y Outbox (`0 PII`)
- [ ] **Histórico (`reservations.reservation_history`, si aplica a `MOD-05`):** Se registró la transición de estado en la misma transacción local (o se revirtió si hubo rollback).
- [ ] **Auditoría Inmutable (`traceability.audit_logs` con `Propagation.REQUIRES_NEW`, `ADR-012`):**
  - Tanto en éxito como en rechazo de negocio/seguridad, **existe el asiento en `traceability.audit_logs`** (persistido en transacción independiente `REQUIRES_NEW` aunque la transacción de dominio haya hecho rollback), conteniendo el `requestId`, el `ErrorCode` simbólico (si falló) y **`0 bytes de PII`**.
- [ ] **Outbox Transaccional (`events.outbox_events` con `SKIP LOCKED`):**
  - En éxito: se insertó el evento con `schemaVersion` explícita y **`0 PII`** en su payload JSON (solo identificadores UUID).
  - En rechazo: no se emitió ningún evento en `events.outbox_events`.
```

---

## 3. Suite Obligatoria de Seguridad Perimetral, Ámbitos y Privacidad (`PRU-SEG-01..10` en `PRU-07`)

| ID Escenario | Qué Verifica el Test de Seguridad | Resultado Exigido |
| :---: | :--- | :--- |
| **`PRU-SEG-01`** | Intento de acceso a rutas de `platform.resourcehub.app` desde un host organizativo `{orgSlug}.resourcehub.app` (o viceversa) | Rechazo inmediato en `COMP-GW` (`HTTP 403`/`404`) antes de alcanzar ningún módulo (`DTD-001`) |
| **`PRU-SEG-02`** | Cruce de Ámbito Operacional en `COMP-GW`: token emitido para `/app` (`AMB-01`) intenta invocar `/api/v1/workspace/**` (`AMB-02`) o `/api/v1/console/**` (`AMB-03`) | `HTTP 403 Forbidden` con `ResponseWrapper` `code = 200003` + registro en `traceability.audit_logs` (`DTD-001`, `DTD-002`) |
| **`PRU-SEG-03`** | Intento de un cliente externo de inyectar cabeceras internas de diagnóstico RFC 9457 para obtener `application/problem+json` | `COMP-GW` elimina incondicionalmente las cabeceras internas en tráfico exterior y devuelve solo `ResponseWrapper` KO saneado (`DTD-002`) |
| **`PRU-SEG-04`** | Login acotado por canal (`/api/v1/{app|workspace|console}/auth/login`): intento de autenticar un actor sin rol permitido en el ámbito del canal | Rechazo en autenticación del canal sin emitir JWT para ese ámbito (`DTD-001`) |
| **`PRU-SEG-05`** | En canal `/api/v1/app/**` (`AMB-01`): cliente intenta crear/consultar/cancelar una reserva donde `holder != requester` | Rechazo de autorización + asiento en `traceability.audit_logs` (`REQUIRES_NEW`) |
| **`PRU-SEG-06`** | Acceso Cross-Tenant: token de `org-beta` solicita o muta un recurso UUID existente en `org-alfa` | Aislamiento por `TenantConnectionRouter` / `OrganizationContext` devolviendo respuesta opaca `HTTP 404` (`code = 300001`) con `0` fuga de existencia (`PRU-08`) |
| **`PRU-SEG-07`** | Webhook entrante en `/api/v1/webhooks/inbound/**` con firma `HMAC-SHA256` inválida o timestamp fuera de la ventana anti-replay de `300 s` | Rechazo `HTTP 401`/`403` en `COMP-GW` / `MOD-09` / `MOD-10` sin procesar efectos (`DTD-008`) |
| **`PRU-SEG-08`** | Rotación y detección de reutilización de Refresh Token o API Key M2M (`/api/v1/external/**`) revocada/sin scope | Revocación de sesión/familia de tokens + rechazo `HTTP 401`/`403` + auditoría `REQUIRES_NEW` |
| **`PRU-SEG-09`** | Ejecución de baja y olvido RGPD (`CU-ACC-002`, `AC-023`) sobre un usuario con historial de reservas, pagos y auditoría | Criptoborrado/supresión en `access` (*PII Vault*, `ADR-015`) verificando **`0 bytes de PII`** remanentes en los 11 esquemas, `audit_logs`, `outbox_events` y backups lógicos, conservando la integridad referencial estadística |
| **`PRU-SEG-10`** | Envío de cabecera `X-Request-Id` maliciosa (`> 128` caracteres o con caracteres de control/inyección) | `COMP-GW` descarta el valor inseguro, regenera un `requestId` limpio y lo devuelve en `X-Request-Id` y `ResponseWrapper.requestId` (`PRU-04` punto 8) |
