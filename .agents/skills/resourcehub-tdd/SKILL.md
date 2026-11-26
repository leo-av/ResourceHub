---
name: resourcehub-tdd
description: Aplica el Estándar de Desarrollo TDD (Test-Driven Development, RH-EST-TDD-v1.0) y la Estrategia de Pruebas (RH-EST-PRU-v1.0) de ResourceHub. Úsala para implementar cualquier tarea técnica (TT-*), historia de usuario (HU-*), corrección de bug o refactorización siguiendo el ciclo estricto RED -> GREEN -> REFACTOR sobre las capas XF (repository -> business -> api -> COMP-GW), ejecutando los 11 niveles de prueba (PRU-01 a PRU-11) con Testcontainers PostgreSQL 16+ real, Triple Aserción y verificación de las 8 reglas ResourceHub más las 50 reglas XF.
---

# SKILL: `resourcehub-tdd` — Desarrollo Guiado por Pruebas en ResourceHub

| Metadato | Valor |
| :--- | :--- |
| **Identificador de SKILL** | `RH-SKILL-TDD` |
| **Nombre de activación** | `resourcehub-tdd` |
| **Versión de la SKILL** | `v1.0.0` |
| **Fecha de vigencia** | `2026-11-24` |
| **Ámbito** | Específica del proyecto **ResourceHub** (`.agents/skills/resourcehub-tdd/`) |
| **Estándares base (`APPROVED`)** | `ResourceHub — Estándar de Desarrollo TDD v1.0` (`RH-EST-TDD-v1.0`), `Estrategia de Pruebas v1.0` (`RH-EST-PRU-v1.0`), `Diseño Técnico Detallado v1.0` (`RH-DIS-TEC-v1.0`), `Diseño Técnico de Seguridad v1.0` (`RH-DIS-TEC-SEG-v1.0`) |
| **SKILLs complementarias** | `resourcehub-development-workflow` (orquestador), `resourcehub-sdd` (especificación previa obligatoria), `xf-architecture` (`DTD-003`), `response-wrapper` (`DTD-007`) |
| **Recursos empaquetados** | [`references/test-levels-and-triple-assertion.md`](references/test-levels-and-triple-assertion.md) |

---

## 1. Objetivo

Esta SKILL convierte el **Estándar de Desarrollo TDD (`RH-EST-TDD-v1.0`)** y la **Estrategia de Pruebas (`RH-EST-PRU-v1.0`)** de ResourceHub en instrucciones operativas ejecutables para agentes de IA. Su propósito es asegurar que **todo código de producción se escriba únicamente después de haber verificado un test automatizado en estado `RED` que falle por el motivo funcional exacto**, construyendo los componentes en el orden de capas del patrón XF (`repository` → `business` → `api` → `COMP-GW`), probando la persistencia y concurrencia contra **PostgreSQL 16+ real en `Testcontainers`** (`RNF-MANT-002`) y certificando en cada iteración la **Triple Aserción** y las **8 Reglas Arquitectónicas de ResourceHub + 50 Reglas de Conformidad XF (`PRU-02`)**.

> [!IMPORTANT]
> **Prohibiciones Absolutas en `resourcehub-tdd`:**
> 1. **Prohibido** escribir código productivo antes de ejecutar y confirmar la fase `RED` (y prohibido aceptar un *falso RED* causado por errores de compilación o configuración de contenedor).
> 2. **Prohibido** desactivar (`@Disabled`, `.skip`), comentar, borrar o debilitar aserciones de tests para forzar un falso `GREEN`.
> 3. **Prohibido** sustituir `Testcontainers PostgreSQL 16+` por H2, SQLite o mocks de repositorio en pruebas de persistencia, `EXCLUDE USING gist`, `ltree`, bloqueos pesimistas, *PII Vault* o sincronización del catálogo de errores.
> 4. **Prohibido** escribir códigos numéricos `FFMMCC` directamente dentro de los módulos de negocio (`MOD-01..10`); los módulos solo emiten el ENUM simbólico `ErrorCode` de `MOD-11`.

---

## 2. Condiciones de Activación

Activa `resourcehub-tdd` obligatoriamente cuando:

1. **Implementación de una Tarea Técnica (`TT-001..106`) o Historia (`HU-001..052`):** Una vez que `resourcehub-sdd` ha certificado que su `SPEC-<MOD>-<NNN>` supera los Quality Gates `QG-01` y `QG-02`.
2. **Creación o ampliación de pruebas (`PRU-01` a `PRU-11`):** Al desarrollar tests unitarios de reglas `RN-*`, tests de conformidad XF (`PRU-02`), tests de persistencia PostgreSQL 16+ (`PRU-03`), tests de contrato `ResponseWrapper<T>` (`PRU-04`), tests funcionales de casos de uso (`PRU-05`), tests E2E (`PRU-06`), tests de seguridad (`PRU-07`), multi-tenancy (`PRU-08`), concurrencia (`PRU-09`), resiliencia (`PRU-10`) o rendimiento (`PRU-11`).
3. **Corrección de defectos (*Bugfixing*):** Antes de corregir cualquier bug, para reproducirlo primero mediante un test automatizado en estado `RED`.
4. **Refactorización segura:** Al mejorar el diseño interno de `repository/logic`, `business/logic`, `api/logic` o `COMP-GW` manteniendo el 100% de la suite y `PRU-02` en verde.

---

## 3. Verificación Previa a la Codificación (Pre-Flight Checklist de 8 Pasos)

Antes de iniciar la primera fase `RED`, verifica los **8 requisitos de entrada**. Si falta cualquiera de ellos, **detén el inicio de TDD** y vuelve a `resourcehub-sdd`:

1. **Ficha `SPEC-<MOD>-<NNN>` aprobada y completa:** Las 15 secciones están presentes y los gates `QG-01` y `QG-02` están en estado `PASS`.
2. **Mapa de componentes XF cerrado (Sección 6 de la `SPEC-*`):** Están identificados los nombres exactos de `*Repository` (`repository/logic`), `*Business` (`business/logic`), `*Service` o `*View` (`api/logic`) y las estructuras puras en `structs/` (**sin sufijos `DTO`, `Entity` ni `Model`**) más `*Exception`.
3. **Escenarios *Given / When / Then* enumerados (Sección 14 de la `SPEC-*`):** Cada escenario positivo, borde y negativo está mapeado a su nivel `PRU-01..11`.
4. **Catálogo de errores verificado (Sección 13 de la `SPEC-*`):** Todos los valores `ErrorCode` ENUM están declarados en `MOD-11` y tienen su fila correspondiente en `platform_catalog.error_catalog` (`FFMMCC`).
5. **Dependencias `TT-*` previas en `DONE`:** Verificado en la `Matriz de Dependencias del Backlog v1.0`.
6. **Sincronización previa en tareas coordinadas:** Si la tarea tiene modo `COORDINADA`, el contrato compartido (`B.*`, evento o `ErrorCode`) está bloqueado/acordado antes de codificar.
7. **Entorno PostgreSQL 16+ (`Testcontainers`) operativo:** Para niveles `PRU-03` a `PRU-11`, el contenedor PostgreSQL 16+ con migraciones Flyway (`platform` y `org`) arranca limpiamente.
8. **Línea base en verde:** La suite de pruebas existente pasa al 100% antes de introducir cambios.

---

## 4. Ciclo Obligatorio `RED -> GREEN -> REFACTOR` Integrado con XF y `ResponseWrapper`

Ejecuta un ciclo iterativo por cada escenario *Given / When / Then* de la Sección 14 de la `SPEC-<MOD>-<NNN>`:

### 4.1. Fase `RED` — Prueba fallida por la aserción funcional exacta (`QG-03`)

1. Selecciona **un único escenario *Given / When / Then* o regla de negocio `RN-*`** de la ficha `SPEC-<MOD>-<NNN>`.
2. Escribe la prueba apuntando al contrato correspondiente según la capa XF:
   - En lógica pura de negocio (`PRU-01`): invoca `B.<negocio>.<operacion>()` en memoria con `Clock` inyectable y **0 mocks**, verificando retornos de `structs/` puros o el lanzamiento de `*Exception` con el `ErrorCode` ENUM exacto.
   - En persistencia/casos de uso transaccionales (`PRU-03`, `PRU-05`, `PRU-09`): ejecuta contra **PostgreSQL 16+ en `Testcontainers`** e incluye obligatoriamente la **Triple Aserción** (ver [`references/test-levels-and-triple-assertion.md`](references/test-levels-and-triple-assertion.md)).
   - En borde API y Gateway (`PRU-04`, `PRU-07`, `PRU-08`): invoca a través de `COMP-GW` verificando el contrato `ResponseWrapper<T>` (`code` entero `FFMMCC`, `requestId == X-Request-Id`, `timestamp` ISO-8601 UTC, exclusión mutua OK vs KO).
3. Ejecuta el test y **certifica `QG-03` (`RED` válido):** el test debe fallar por la aserción funcional esperada, **nunca** por un fallo de arranque de `Testcontainers`, error de sintaxis o configuración rota (*falso RED* prohibido).

### 4.2. Fase `GREEN` — Implementación mínima en el orden de capas XF

Implementa **únicamente el código mínimo necesario** para que el test pase a `GREEN`, respetando el orden estricto de construcción de `.agents/skills/xf-architecture/SKILL.md` (`repository` → `business` → `api` → `COMP-GW`):

1. **En `repository/logic/*Repository` (expuesto por fachada `R`):**
   - Implementa el acceso SQL explícito o externo siguiendo las 5 etapas invariantes XF y propagando `*Exception` tipadas.
   - Trabaja con estructuras puras de `structs/` y aplica siempre el filtro/contexto de organización (`OrganizationContext`).
2. **En `business/logic/*Business` (expuesto por fachada `B`):**
   - Implementa las guardas de negocio (`RN-*`), el orden de bloqueo canónico (`ADR-008`), la liberación síncrona en línea de `PENDING` vencidas (`MOD-ARQ-09`) y la demarcación transaccional (`REQUIRED` + auditoría en `REQUIRES_NEW` `ADR-012`).
   - En caso de error de negocio, lanza la excepción del módulo portando **únicamente el ENUM simbólico `ErrorCode` de `MOD-11`** (queda prohibido escribir enteros `FFMMCC` en `MOD-01..10`).
3. **En `api/logic/services/*Service` (o `api/logic/gui/*View`) y `COMP-GW`:**
   - En `*Service`: expone el recurso delegando exclusivamente en la fachada `B` (y serializando errores internos como `application/problem+json` RFC 9457 con el `ErrorCode` simbólico).
   - En `COMP-GW`: valida host y ámbito operacional, consulta la caché en memoria de `platform_catalog.error_catalog`, traduce el `ErrorCode` simbólico al `numeric_value` (`FFMMCC`) y sanea la respuesta exterior al sobre cerrado `ResponseWrapper<T>` con cabecera `X-Request-Id`.
4. Ejecuta el test y comprueba que pasa a **`GREEN`**.

### 4.3. Fase `REFACTOR` — Limpieza + Doble Conformidad Arquitectónica (`PRU-02`)

1. Con el test en `GREEN`, elimina código duplicado, extrae utilidades puras a `*/utils/*Utils` si procede y asegura la inmutabilidad de las estructuras en `structs/`.
2. Ejecuta obligatoriamente **`PRU-02`** verificando **`0 violaciones`** en:
   - Las **8 Reglas Arquitectónicas de ResourceHub (`RNF-MANT-001`)**.
   - Las **50 Reglas de Conformidad XF (`.agents/skills/xf-architecture/SKILL.md`)**.
3. Ejecuta toda la suite de regresión del módulo. Solo cuando todo está en `GREEN` puedes pasar al siguiente escenario de la `SPEC-<MOD>-<NNN>` (`QG-04`).

> [!NOTE]
> **Excepciones justificadas al ciclo unitario previo (`RH-EST-TDD-v1.0 §2`):**
> Los scripts DDL de `Flyway` se verifican directamente mediante los tests de integración `PRU-03` (constraints, GiST, `ltree`, triggers); la suite estática `PRU-02` (`ArchUnit` + 50 reglas XF) se crea en `MVP-0` y se ejecuta en cada `REFACTOR`; y el CSS puramente visual en `api/logic/gui/*View` no exige test unitario previo, mientras que la lógica de presentación, el formateo `formatMoneyMinor` y los flujos E2E (`PRU-06`) sí siguen TDD estricto.

---

## 5. Estrategia de Pruebas por Nivel (`PRU-01` a `PRU-11`)

La matriz completa de alcance, herramientas y puertas de CI para cada uno de los **11 niveles de prueba (`PRU-01` a `PRU-11`)** se encuentra en [`references/test-levels-and-triple-assertion.md`](references/test-levels-and-triple-assertion.md). Todo agente debe seleccionar y ejecutar los niveles aplicables conforme a estas reglas:

- **`PRU-01` (Unitarias de Negocio en `business/logic/*Business`):** `0 mocks` de dominio, `Clock` inyectable, cobertura **≥ 90% de ramas lógicas** en `business/logic` y verificación del `ErrorCode` ENUM lanzado.
- **`PRU-02` (Conformidad Arquitectónica ResourceHub + XF):** Puerta 2 de CI obligatoria desde `MVP-0` (`0 violaciones` en las **8 reglas ResourceHub + 50 reglas XF**).
- **`PRU-03` (Integración y Persistencia Relacional en `Testcontainers PostgreSQL 16+`):** Verifica migraciones Flyway (`platform` y `org`), **biyección 1:1 entre todos los valores de `ErrorCode` (`MOD-11`) y las filas activas de `platform_catalog.error_catalog` (`DTD-011`)**, `EXCLUDE USING gist`, `ltree`, triggers *append-only*, `REQUIRES_NEW` y Outbox `SKIP LOCKED`.
- **`PRU-04` (Contratos Internos, API Gateway y `ResponseWrapper`):** Verifica los **9 puntos obligatorios de `skills/response-wrapper/SKILL.md`** en `COMP-GW` (código `FFMMCC`, `requestId == X-Request-Id`, `timestamp` UTC, exclusión mutua OK/KO, traducción desde RFC 9457 sin fugas técnicas, `404` `300001`, fallback estático `500` `900001`/`900002`, regeneración de `X-Request-Id` inseguro `>128 chars`, y 0 códigos numéricos mágicos en handlers).
- **`PRU-05` (Funcionales de Casos de Uso con Triple Aserción):** Verifica los `26 CU` y `12 PF` exigiendo en cada mutación la **Triple Aserción**: (a) `ResponseWrapper<T>` exterior (y RFC 9457 interno), (b) estado exacto en PostgreSQL 16+ (o estado intacto tras rollback), y (c) histórico `reservation_history`, auditoría `traceability.audit_logs` (`REQUIRES_NEW`, con `ErrorCode`, `requestId` y `0 PII`) y evento Outbox (`events.outbox_events` con `0 PII`).
- **`PRU-06` (Aceptación *Given / When / Then*):** Verifica los `23 AC` y `74 escenarios` (por API en `MVP-N` y en navegador Playwright sobre `EXP-01`/`EXP-02` en `MVP-(N+1)` según `D-38`).
- **`PRU-07` (Seguridad, Ámbitos en Gateway y RGPD — `PRU-SEG-01..10`):** Verifica separación de host `platform.resourcehub.app` vs `{orgSlug}.resourcehub.app`, cruce de ámbitos `/app`, `/workspace`, `/console` -> `403` (`200003`), eliminación de cabeceras internas desde el exterior, firmas `HMAC-SHA256` + ventana anti-replay de 300 s, y `0 bytes de PII` tras borrado RGPD (`CU-ACC-002`, `AC-023`).
- **`PRU-08` (Aislamiento Multi-Tenant Físico y Lógico):** Verifica `TenantConnectionRouter` con 2 BDs físicas (`org-alfa`, `org-beta`), `0` fugas cross-tenant, respuesta opaca `404` (`300001`), bloqueo de tenants `SUSPENDED`/`ARCHIVED` y desalojo LRU de pools (`AC-002`).
- **`PRU-09` (Concurrencia y Anti-Deadlocks Multihilo):** `N` hilos simultáneos sobre `capacity = 1` (`AC-004`), `capacity = K`, reservas compuestas con orden canónico (`AC-012`), jerarquía padre-hijo `ltree` (`AC-013`) y liberación síncrona en línea de `PENDING` vencidas (`AC-014`).
- **`PRU-10` (Resiliencia de Integraciones y Compensación):** Caída de `platform_db` (activación de fallback estático en `COMP-GW`), webhooks en cuarentena (`QUARANTINED`, `ADR-017`), colisión con calendario externo (`AC-019`) y **reembolso compensatorio automático del 100% ante cobro tardío sobre reserva `EXPIRED` (`MOD-ARQ-09`)**.
- **`PRU-11` (Rendimiento, Benchmarks y Regresión):** Particiones mensuales (`MDD-015`), paginación `/api/v2` por cursor (`CursorPageResponse<T>`), verificación de que `MOD-05 reservas` no usa Redis para conflictos (`RNF-PERF-003`) y `0` regresiones en toda la suite acumulada.

---

## 6. Reglas Específicas de ResourceHub que `resourcehub-tdd` Exige en Cada Prueba

Todo test escrito por un agente debe hacer cumplir explícitamente las siguientes invariantes arquitectónicas de ResourceHub:

1. **Las 8 Reglas Arquitectónicas de ResourceHub (`RNF-MANT-001`) + 50 Reglas XF (`DTD-003`) en `PRU-02`:**
   - (1) `business` limpio de dependencias de transporte/framework web; (2) DAG acíclico estricto entre `MOD-01..11`; (3) `0` claves foráneas (`FK`) ni `JOIN` SQL entre esquemas de distintos módulos (`ADR-007`); (4) `OrganizationContext` inmutable obligatorio en operaciones de organización; (5) `0 PII` fuera del esquema `access` (`MOD-02`, `ADR-015`); (6) escritura de auditoría en `traceability.audit_logs` con `Propagation.REQUIRES_NEW` (`ADR-012`); (7) `0` uso de `float`/`double` para importes monetarios (uso exclusivo de `MoneyMinor` en unidades mínimas enteras); (8) uso obligatorio de `Clock` explícito (prohibido llamar al reloj del sistema directamente en lógica de negocio).
   - Más las **50 reglas de `.agents/skills/xf-architecture/SKILL.md`**: 3 capas cerradas (`api`, `business`, `repository`), 5 tipos cerrados (`logic/`, `base/`, `R`/`B`/`A`/`XF`, `utils/`, `structs/`), prohibición de sufijos `DTO`/`Entity`/`Model` en `structs/`, aislamiento de `base` y `utils`, y delegación estricta `A -> B -> R`.
2. **Doble Canal de Errores (`DTD-002` + `DTD-011`):**
   - En tests de módulo (`PRU-01`, `PRU-03`): se afirma el `ErrorCode` ENUM simbólico (`MOD-11`).
   - En tests de Gateway (`PRU-04`, `PRU-05`, `PRU-07`): se afirma que `COMP-GW` oculta el RFC 9457 (`application/problem+json`) al cliente externo y devuelve `ResponseWrapper` KO con el entero `code = FFMMCC` traducido desde `platform_catalog.error_catalog`.
3. **Triple Aserción Obligatoria en Mutaciones:** Verificación simultánea de respuesta (`ResponseWrapper<T>`), estado en PostgreSQL 16+ y trazabilidad (`reservation_history` + `audit_logs` en `REQUIRES_NEW` + `outbox_events` con `0 PII`).

---

## 7. Gestión de Fallos de Pruebas y Regresiones

Ante cualquier test en estado `RED` inesperado o regresión:

1. **Si falla un test nuevo durante la fase `GREEN`:** Inspecciona la implementación en `*Repository`, `*Business`, `*Service` o `COMP-GW` y corrige la lógica de producción sin alterar ni suavizar la aserción del test.
2. **Si falla un test de regresión existente:** Tu cambio ha violado un contrato o regla previa. Revierte o corrige el código productivo inmediatamente; **está prohibido modificar un test de regresión existente para que pase**, salvo que provenga de un cambio de especificación previamente aprobado en `resourcehub-sdd` (*Spec-First*).
3. **Si un test demuestra que la `SPEC-<MOD>-<NNN>` es contradictoria o inviable:** Detén `resourcehub-tdd` (*Stop-the-Line*), no inventes un parche en el código ni en el test, y regresa a `resourcehub-sdd` / `resourcehub-development-workflow` para escalar el hallazgo.

---

## 8. Criterios de Finalización (Definition of Done TDD en 3 Niveles, `RH-EST-PRU-v1.0 §3`)

### Nivel 1 — DoD de Tarea Técnica (`TT-*`) e Historia de Usuario (`HU-*`) (`QG-03` y `QG-04`)
- [ ] Todos los escenarios *Given / When / Then* de la `SPEC-<MOD>-<NNN>` han completado el ciclo `RED -> GREEN -> REFACTOR`.
- [ ] Triple Aserción verificada en todas las pruebas de persistencia y casos de uso sobre `Testcontainers PostgreSQL 16+`.
- [ ] **0 violaciones** en `PRU-02` (**8 Reglas ResourceHub + 50 Reglas XF**) y **0 números mágicos `FFMMCC`** dentro de los módulos `MOD-01..10`.
- [ ] Cobertura de ramas lógicas **≥ 90%** en `business/logic/*Business`.

### Nivel 2 — DoD de Funcionalidad (`FUN-*`) (`QG-05`)
- [ ] Superación al **100%** de las 6 Puertas de CI (`RH-DIS-TEC-v1.0 §6.2`): Tipado/Compilación + `PRU-02` (ArchUnit + 50 Reglas XF) + `PRU-01` (Unitarias) + `PRU-03`/`PRU-05`/`PRU-09` (`Testcontainers PostgreSQL 16+`, sync 1:1 `ErrorCode <-> platform_catalog.error_catalog`, GiST, `ltree`, *PII Vault*) + `PRU-04`/`PRU-07`/`PRU-08` (`ResponseWrapper<T>`, ámbitos de Gateway y multi-tenancy).

### Nivel 3 — DoD de Entrega (`MVP-*` / `HITO-01..08`) (`QG-06`)
- [ ] 100% de las `SPEC-*`, `HU-*` y `TT-*` del MVP en estado `DONE`, pruebas E2E (`PRU-06`), resiliencia (`PRU-10`) y benchmarks (`PRU-11`) aplicables al hito en verde, y revisión humana aprobada.

---

## 9. Formato de Salida Esperado de `resourcehub-tdd`

Al concluir la ejecución de `resourcehub-tdd`, entrega siempre este informe estructurado:

```markdown
## Informe de Ejecución TDD — `SPEC-<MOD>-<NNN>` (`<TT-* / HU-*>`)

### 1. Verificación Pre-Flight (8 Pasos)
- Estado de entrada (`SPEC-*` aprobada, mapa XF, contenedor PostgreSQL 16+): `PASS`

### 2. Evidencia de Ciclos `RED -> GREEN -> REFACTOR` por Capa XF
| # | Escenario (`SPEC-* §14`) | Nivel `PRU-*` | Capa XF (`R` / `B` / `A` / `COMP-GW`) | Evidencia `RED` (`QG-03`) | Componente Implementado (`GREEN`) | `REFACTOR` + `PRU-02` (8 RH + 50 XF) |
| :-: | :--- | :-: | :--- | :--- | :--- | :-: |
| 1 | `ESC-01` | `PRU-01` / `PRU-03` | `repository` -> `business` | Fallo funcional verificado | `*Repository`, `*Business` | `0 violaciones` |
| ... | ... | ... | ... | ... | ... | ... |

### 3. Verificación de Triple Aserción y Catálogo de Errores
- Contrato `ResponseWrapper<T>` + `X-Request-Id` (`PRU-04`): `PASS`
- Estado en PostgreSQL 16+ (`PRU-03` / `PRU-05`): `PASS`
- Trazabilidad (`reservation_history` + `traceability.audit_logs` `REQUIRES_NEW` + `events.outbox_events` `0 PII`): `PASS`
- Sincronización 1:1 `ErrorCode <-> platform_catalog.error_catalog`: `PASS`

### 4. Dictamen de Quality Gates TDD (`QG-03`, `QG-04`, `QG-05`)
- `QG-03` (`RED` funcional válido): `PASS`
- `QG-04` (100% escenarios de `SPEC-*`, cobertura `business/logic ≥ 90%`, `PRU-02` `0 violaciones`): `PASS`
- `QG-05` (100% Puertas CI y Triple Aserción en verde): `PASS`
```

---

## 10. Versionado y Mantenimiento de la SKILL

- **Versión actual:** `v1.0.0` (sincronizada con `RH-EST-TDD-v1.0` y `RH-EST-PRU-v1.0`).
- **Regla de evolución (`DTD-015`):** Todo cambio aprobado en el `Estándar de Desarrollo TDD`, en la `Estrategia de Pruebas`, en `.agents/skills/xf-architecture/SKILL.md` o en `.agents/skills/response-wrapper/SKILL.md` obliga a actualizar esta SKILL y [`references/test-levels-and-triple-assertion.md`](references/test-levels-and-triple-assertion.md) incrementando su versión semántica.
