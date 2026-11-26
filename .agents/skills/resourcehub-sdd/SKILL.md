---
name: resourcehub-sdd
description: Aplica el Estándar de Desarrollo SDD (Specification-Driven Development, RH-EST-SDD-v1.0) de ResourceHub. Úsala antes de implementar o diseñar tests cuando necesites localizar fuentes en la jerarquía documental de 9 niveles, analizar unidades del Backlog Técnico Implementable (FUN-*, HU-*, TT-*), elaborar o actualizar especificaciones SPEC-<MOD>-<NNN> con sus 15 secciones oficiales (arquitectura XF R/B/A/structs, canales COMP-GW, ResponseWrapper<T>, ErrorCode ENUM -> FFMMCC), validar Quality Gates QG-01 y QG-02, gestionar ambigüedades sin inventar reglas o aplicar cambios bajo la regla Spec-First.
---

# SKILL: `resourcehub-sdd` — Desarrollo Guiado por Especificación en ResourceHub

| Metadato | Valor |
| :--- | :--- |
| **Identificador de SKILL** | `RH-SKILL-SDD` |
| **Nombre de activación** | `resourcehub-sdd` |
| **Versión de la SKILL** | `v1.0.0` |
| **Fecha de vigencia** | `2026-11-24` |
| **Ámbito** | Específica del proyecto **ResourceHub** (`.agents/skills/resourcehub-sdd/`) |
| **Estándares base (`APPROVED`)** | `ResourceHub — Estándar de Desarrollo SDD v1.0` (`RH-EST-SDD-v1.0`), `Diseño Técnico Detallado v1.0` (`RH-DIS-TEC-v1.0`), `Especificación de Interfaces y Contratos v1.0` (`RH-ESP-INT-CON-v1.0`), `Catálogo de Especificaciones de Implementación v1.0` (`RH-CAT-ESP-IMP-v1.0`) |
| **SKILLs complementarias** | `resourcehub-development-workflow` (orquestador), `resourcehub-tdd` (implementación TDD), `xf-architecture` (`DTD-003`), `response-wrapper` (`DTD-007`) |
| **Recursos empaquetados** | [`references/spec-template-and-checklist.md`](references/spec-template-and-checklist.md) |

---

## 1. Objetivo

Esta SKILL convierte el **Estándar de Desarrollo SDD (`RH-EST-SDD-v1.0`)** de ResourceHub en un procedimiento operativo obligatorio para agentes de IA. Su objetivo es garantizar los **10 Principios Obligatorios de SDD**: **ninguna funcionalidad (`FUN-001..048`), historia de usuario (`HU-001..052`) ni tarea técnica (`TT-001..106`) se implementa sin una especificación técnica previa (`SPEC-<MOD>-<NNN>`) completa en sus 15 secciones, trazable, sin bloqueos abiertos y coherente con la jerarquía documental de 9 niveles y las decisiones aprobadas (`DTD-001..015`, `MRD-001..013`, `MDD-001..015`)**.

> [!IMPORTANT]
> **Regla Innegociable de `resourcehub-sdd` (*Spec-First*):**
> La especificación `SPEC-<MOD>-<NNN>` precede siempre al test y al código. Está terminantemente prohibido implementar sin especificación suficiente, hacer suposiciones silenciosas ante ambigüedades `BLOCKER` o `HIGH`, inventar reglas de negocio o códigos fuera de `ErrorCode` (`MOD-11`) / `platform_catalog.error_catalog`, o alterar retrospectivamente una especificación aprobada para justificar una implementación defectuosa.

---

## 2. Condiciones de Activación

### 2.1. Cuándo DEBE activarse esta SKILL

Activa `resourcehub-sdd` obligatoriamente en cualquiera de las siguientes situaciones:

1. **Inicio de análisis de una unidad de trabajo:** Al abordar cualquier `FUN-*`, `HU-*` o `TT-*` del `Backlog Técnico Implementable v1.0` (`RH-BACK-TEC-IMP-v1.0`).
2. **Elaboración, expansión o revisión de una `SPEC-<MOD>-<NNN>`:** Al redactar o detallar cualquiera de las 49 fichas de especificación backend/frontend del `Catálogo de Especificaciones de Implementación v1.0` (`RH-CAT-ESP-IMP-v1.0`) en sus 15 secciones obligatorias.
3. **Evaluación de Quality Gates `QG-01` y `QG-02`:** Antes de autorizar la transición a `resourcehub-tdd`, para certificar ausencia de bloqueos `BLOCKER`/`HIGH`, resolución de Entry Gates (`MRD-007`) y cumplimiento 100% de XF, DAG, *PII Vault*, `ResponseWrapper<T>` y reglas `RN-*`/`CU-*`.
4. **Detección de ambigüedades, huecos o contradicciones:** Cuando durante el análisis o durante una iteración TDD se detecte un escenario no cubierto o una colisión entre fuentes documentales.
5. **Gestión de cambios (*Spec-First Change Management*):** Cuando se requiera modificar o incorporar un contrato REST, un componente XF, una tabla/columna PostgreSQL (`PCR-DAT-*`), una regla de negocio o un nuevo código de error siguiendo el protocolo de 4 pasos de `DTD-011`.
6. **Auditoría de conformidad SDD:** Al verificar si un módulo implementado cumple fielmente su `SPEC-<MOD>-<NNN>` aprobada.

### 2.2. Ejemplos de peticiones que activan `resourcehub-sdd`

- *"Analiza la tarea `TT-011` (`HU-008`) y verifica o completa su especificación `SPEC-RES-001` antes de pasar a TDD."*
- *"Evalúa los Quality Gates `QG-01` y `QG-02` sobre `SPEC-ACC-001` para el módulo `MOD-02 acceso`."*
- *"Necesitamos añadir un código de error específico en `MOD-05 reservas`; aplica el protocolo SDD y `DTD-011`."*
- *"Audita si el contrato de bloqueo de recursos con previsualización forzada (`D-44`) en `SPEC-RES-003` cumple `DTD-001`, `DTD-002`, `DTD-007` y `DTD-011`."*

---

## 3. Procedimiento Operativo Paso a Paso

Ejecuta secuencialmente los **Pasos A → E** en toda intervención bajo `resourcehub-sdd`.

### Paso A — Localización de Fuentes y Jerarquía Documental de 9 Niveles

1. **Lectura Contextual Acotada (Cero Lectura Indiscriminada):**
   Identifica el módulo de la tarea (`MOD-01` a `MOD-11`, `COMP-GW`, `EXP-01` o `EXP-02`) y consulta **únicamente el Paquete de Contexto Mínimo Obligatorio** definido en `Guía de Preparación para Desarrollo con Agentes v1.0 §3` (y referenciado en `resourcehub-development-workflow/references/context-packages-and-gates.md`).
2. **Respeto Estricto a la Jerarquía de Autoridad Documental (9 Niveles):**
   Toda especificación se deriva exclusivamente de las fuentes aprobadas (`APPROVED`), aplicando la siguiente prelación de autoridad descendente:

| Nivel | Documentos Vigentes (`APPROVED`) | Alcance Normativo e Identificadores |
| :---: | :--- | :--- |
| **1** | `ResourceHub — Documento Maestro de Producto y Sistema v1.5.md` (`docs/00-project/`) | Visión, 11 módulos, `D-1..D-47`, `AC-001..023`, ámbitos `AMB-01..03` y `PLATFORM` |
| **2** | `Especificación Funcional v1.0.md` y `Especificación No Funcional v1.0.md` (`docs/01-requirements/`) | `42 RF` (`RF-ORG`, `RF-ACC`, `RF-CAT`, `RF-DIS`, `RF-RES`, `RF-FLU`, `RF-POL`, `RF-TRA`, `RF-INT`, `RF-PAG`, `RF-EXP`) y `40 RNF` |
| **3** | `Modelo Funcional v1.0.md` (`docs/02-functional-specification/`), `Catálogo de Casos de Uso v1.0.md` (`docs/03-use-cases/`), `Catálogo de Reglas de Negocio v1.0.md` (`docs/04-business-rules/`) | `12 PF`, `26 CU` (`74 escenarios`), `28 RN` y `6 ciclos de estado CIC-01..06` |
| **4** | `Propuesta de Arquitectura v1.1.md`, `Modelo Conceptual del Dominio v1.0.md`, `Modelo Lógico de Datos v1.1.md`, `Modelo Físico de Datos v1.1.md`, `Diccionario de Datos v1.1.md` (`docs/05-architecture/`) | `MOD-01..11`, `COMP-GW`, `34 EL-*`, `34 tablas` en `11 esquemas` (`resourcehub_platform_db` y `resourcehub_org_<slug>_db`), `365 columnas`, `ADR-001..017` |
| **5** | `Diseño Técnico Detallado v1.0.md`, `Especificación de Interfaces y Contratos v1.0.md`, `Diseño Técnico de Seguridad v1.0.md` (`docs/05-architecture/`) | Capas XF, algoritmos transaccionales, canales de Gateway, `ResponseWrapper<T>`, catálogo de 34 códigos `FFMMCC`, seguridad perimetral y *PII Vault* |
| **6** | `Estándar de Desarrollo SDD v1.0.md`, `Estándar de Desarrollo TDD v1.0.md`, `Flujo de Desarrollo SDD y TDD v1.0.md` (`docs/06-technical-decisions/`), `Estrategia de Pruebas v1.0.md` (`docs/05-architecture/`), `.agents/skills/xf-architecture/SKILL.md` y `.agents/skills/response-wrapper/SKILL.md` | Plantilla `SPEC-*` de 15 secciones, 50 reglas XF + 8 reglas ResourceHub, `PRU-01..11`, `QG-01..06` |
| **7** | `Estrategia de MVP v1.1.md`, `Roadmap del Producto v1.1.md` (`docs/07-mvp-roadmap/`) | Entregas `MVP-0` a `MVP-14`, `HITO-01..08`, decalaje Frontend `+1 MVP` (`D-38`) |
| **8** | `Backlog de Alto Nivel v1.1.md`, `Backlog Técnico Implementable v1.0.md`, `Catálogo de Especificaciones de Implementación v1.0.md`, `Matriz de Dependencias del Backlog v1.0.md`, `Matriz de Trazabilidad del Backlog v1.0.md`, `Guía de Preparación para Desarrollo con Agentes v1.0.md` (`docs/07-mvp-roadmap/`) | `3 INI`, `15 EP`, `48 FUN`, `52 HU`, `106 TT` (`TT-001..106`), `49 SPEC-*` |
| **9** | `DECISION-LOG.md`, `Registro de Decisiones Técnicas v1.0/v1.1`, `Decisiones de MVP y Roadmap v1.1`, `Decisiones de Modelo de Dominio y Datos v1.0`, `Registro de Decisiones Arquitectónicas v1.0` (`docs/06-technical-decisions/`) | `DEC-001..092+`, `DTD-001..015`, `PCR-DAT-001`, `MRD-001..013`, `MDD-001..015`, cuestiones `OPEN-*` |

> [!CAUTION]
> **Regla de Prevalencia y Prohibición de Resolución Arbitraria:**
> Las decisiones y documentos `APPROVED` de nivel superior prevalecen sobre niveles inferiores y sobre cualquier propuesta `UNDER REVIEW`. Si detectas una contradicción entre dos documentos `APPROVED`, **no la resuelvas arbitrariamente**: detén la especificación del punto en conflicto, documéntala con citas exactas y escálala al propietario del proyecto (Sección 4).

---

### Paso B — Análisis de la Unidad de Trabajo y Verificación de `QG-01`

1. **Identificación en el Backlog y el Catálogo:**
   - Localiza la ficha de la `TT-*`, `HU-*` y `FUN-*` en `Backlog Técnico Implementable v1.0.md` y la entrada `SPEC-<MOD>-<NNN>` en `Catálogo de Especificaciones de Implementación v1.0.md`.
   - Comprueba que el elemento tiene `Approval = APPROVED` y que sus dependencias previas en `Matriz de Dependencias del Backlog v1.0.md` están satisfechas.
2. **Evaluación del Quality Gate `QG-01` (Bloqueos y Entry Gates `MRD-007`):**
   - Verifica que no exista ninguna cuestión pendiente con severidad `BLOCKER` o `HIGH`.
   - Si la unidad de trabajo tiene asignado un Entry Gate condicional `OPEN-*` (`Definition = NEEDS_CLARIFICATION` según `MRD-007`), verifica si el propietario ya emitió instrucción o autorizó aplicar el **valor por defecto seguro aprobado en `MRD-007`**, dejándolo explícitamente documentado en la Sección 15 de la ficha `SPEC-<MOD>-<NNN>`.
3. **Ubicación Arquitectónica (Módulo, Esquema, Host y Canal):**
   - **Módulo y Esquema:** `MOD-01 plataforma` (`platform` / `platform_catalog`), `MOD-02 acceso` (`access`, *PII Vault*), `MOD-03 recursos` (`resources`), `MOD-04 disponibilidad` (`availability`), `MOD-05 reservas` (`reservations`), `MOD-06 políticas` (`policies`), `MOD-07 trazabilidad` (`traceability`), `MOD-08 eventos` (`events`), `MOD-09 integraciones` (`integrations`), `MOD-10 pagos` (`payments`), `MOD-11 núcleo compartido`, `COMP-GW` (API Gateway), `EXP-01 WebCliente`, `EXP-02 BackOffice`.
   - **Host y Prefijo de Canal (`DTD-001`):**
     - Plano de Plataforma: Host exclusivo `https://platform.resourcehub.app/api/v1/**` (exclusivo `ACT-PA`, inaccesible desde hosts organizativos).
     - Plano de Organización (`https://{orgSlug}.resourcehub.app`, `D-43`):
       - `/api/v1/app/**` (incluye `/api/v1/app/auth/**`) → `AMB-01` (`WebCliente`, `ACT-US`).
       - `/api/v1/workspace/**` (incluye `/api/v1/workspace/auth/**`) → `AMB-02` (`BackOffice` Vista Profesional, `ACT-OP`).
       - `/api/v1/console/**` (incluye `/api/v1/console/auth/**`) → `AMB-03` (`BackOffice` Consola de Administración, `ACT-AO`, `ACT-AR`).
       - `/api/v1/external/**` (incluye `/api/v1/external/auth/token`) → Clientes M2M (`ApiClient`, `MVP-12`).
       - `/api/v1/webhooks/inbound/**` → Callbacks firmados sin JWT (`EXT-CAL`, `EXT-PAG`).

---

### Paso C — Elaboración o Actualización de la Especificación (`SPEC-<MOD>-<NNN>`)

Completa o actualiza la ficha `SPEC-<MOD>-<NNN>` utilizando **obligatoriamente la plantilla oficial de 15 secciones** de `RH-EST-SDD-v1.0 §2`, detallada en [`references/spec-template-and-checklist.md`](references/spec-template-and-checklist.md):

1. **Identificador y Nombre** (`SPEC-<MOD>-<NNN> — <Nombre conciso>`).
2. **Versión, Estado y MVP** (`vX.Y`, estado `DRAFT`/`UNDER REVIEW`/`APPROVED`/`IMPLEMENTED`/`SUPERSEDED`, entrega `MVP-0`..`MVP-14`).
3. **Objetivo y Contexto** (vínculo con `FUN-*`, `HU-*` y `TT-*`).
4. **Alcance Incluido y Exclusiones** (qué entra en este MVP y qué se difiere, respetando el decalaje `+1 MVP` de Frontend `D-38`).
5. **Trazabilidad Normativa** (`RF-*`, `RNF-*`, `PF-*`, `CU-*`, `RN-*`, `AC-*`, `D-*`, `ADR-*`, `MDD-*`, `MRD-*`, `DTD-*`).
6. **Mapa de Componentes XF Implicados (`DTD-003` y `skills/xf-architecture/SKILL.md`):**
   - Detalle de componentes en las **3 capas cerradas XF** (`repository/logic/*Repository` expuesto vía fachada `R`, `business/logic/*Business` vía fachada `B`, `api/logic/services/*Service` o `api/logic/gui/*View` vía fachada `A`) y los **5 tipos cerrados XF** (`logic/`, `base/`, `R`/`B`/`A`/`XF`, `utils/*Utils`, `structs/` sin sufijos `DTO`/`Entity`/`Model` + `*Exception`).
7. **Contratos Afectados (`DTD-001`, `DTD-007` y `skills/response-wrapper/SKILL.md`):**
   - Operación interna en `B` y endpoint REST con host (`platform.resourcehub.app` o `{orgSlug}.resourcehub.app`) y prefijo de canal (`/app`, `/workspace`, `/console`, `/external`, `/webhooks/inbound`), especificando el tipo `T` dentro de `ResponseWrapper<T>` (`{ code, requestId, timestamp, description?, data?, errors? }` + `ErrorDetail { field?, message }` + cabecera `X-Request-Id`), con `PageResponse<T>` (`/api/v1/...`) o `CursorPageResponse<T>` (`/api/v2/...` desde `MVP-7`) dentro de `data`.
8. **Entradas y Validaciones Estructurales** (campos de entrada en `structs/` y validaciones sintácticas asociadas a `ErrorCode.VALIDATION_FAILED` -> `100001`).
9. **Seguridad en Gateway y Dominio** (validación de ámbito en `COMP-GW` `AMB-01..03`, `PLATFORM`, `EXTERNAL_CLIENT`, roles permitidos, `holder == requester` en `/app` y `OrganizationContext` inmutable `D-42`, `D-43`).
10. **Comportamiento Esperado y Flujos** (secuencia principal y flujos alternativos en `business/logic/*Business`, incluyendo algoritmos de bloqueo canónico `ADR-008`, expiración síncrona en línea de `PENDING` vencidas `MOD-ARQ-09` o previsualización `D-44` si aplican).
11. **Persistencia, Transaccionalidad y Concurrencia** (tablas `esquema.tabla` del módulo propietario, demarcación `REQUIRED` vs `REQUIRES_NEW` `ADR-012`, `EXCLUDE USING gist`, `ltree` y confinamiento *PII Vault* `ADR-015` en `MOD-02 acceso`).
12. **Salidas, Eventos y Trazabilidad** (`ResponseWrapper<T>` exterior con `X-Request-Id`, entrada en `reservations.reservation_history`, asiento en `traceability.audit_logs` con `ErrorCode` y `requestId` en `REQUIRES_NEW`, y evento Outbox con `schemaVersion` y `0 PII`).
13. **Catálogo de Errores Esperados (`DTD-002`, `DTD-011`)** (tabla con `{ ErrorCode ENUM emitido por el módulo en RFC 9457 interno, numeric_value FFMMCC traducido por COMP-GW desde platform_catalog.error_catalog, HTTP status, condición }`).
14. **Criterios de Aceptación y Escenarios TDD** (lista numerada *Given / When / Then* positivos, borde y negativos mapeada a `PRU-01`..`PRU-11` que alimenta directamente la fase `RED` de `resourcehub-tdd`).
15. **Dependencias y Decisiones Pendientes** (`SPEC-*` y `TT-*` previas y cuestiones `OPEN-*` aplicables con su valor por defecto `MRD-007`).

---

### Paso D — Validación de la Especificación (`QG-01` y `QG-02`)

Ejecuta el checklist completo de [`references/spec-template-and-checklist.md`](references/spec-template-and-checklist.md):

- **`QG-01` (Ausencia de Bloqueos y Entry Gate Confirmado):**
  - `0` cuestiones `BLOCKER` o `HIGH` abiertas.
  - Si existe `OPEN-*` en `MRD-007`, cuenta con confirmación o valor por defecto documentado en la Sección 15.
- **`QG-02` (Conformidad Arquitectónica XF, DAG, *PII Vault*, `ResponseWrapper` y Cobertura `RN`/`CU`):**
  - Las 15 secciones están completas y respetan las 50 reglas de `xf-architecture` + las 8 reglas de ResourceHub (`RNF-MANT-001`).
  - Los módulos `MOD-01..10` solo emiten valores simbólicos del ENUM `ErrorCode` (`MOD-11`); ningún módulo contiene números mágicos `FFMMCC`.
  - Todos los `ErrorCode` de la Sección 13 existen en `MOD-11` y en `platform_catalog.error_catalog`.
  - Se garantiza `0 PII` fuera del esquema `access` (`ADR-015`).
  - Los escenarios *Given / When / Then* de la Sección 14 cubren el 100% de las reglas `RN-*` y casos de uso `CU-*` asignados a la tarea.

---

### Paso E — Reglas de Aprobación o Parada antes de Pasar a `resourcehub-tdd`

1. **Pase directo a `resourcehub-tdd` (`Execution = READY_FOR_DEVELOPMENT`):**
   - La `SPEC-<MOD>-<NNN>` está en estado `APPROVED`, supera `QG-01` y `QG-02` al 100% y no modifica documentos aprobados de Nivel 1–5.
2. **Parada obligatoria para Aprobación Humana (`Approval = READY_FOR_REVIEW` / `UNDER REVIEW`):**
   - Si la especificación requiere modificar una tabla/columna del `Modelo Físico de Datos v1.1` (propuesta `PCR-DAT-*`), crear un nuevo código de error no incluido en los 34 códigos de `RH-ESP-INT-CON-v1.0 §3` (`DTD-011`), alterar `ResponseWrapper<T>`, añadir una dependencia entre módulos o resolver una cuestión `BLOCKER`/`HIGH`.
   - En cualquiera de estos casos, el agente **tiene prohibido auto-aprobar la especificación o iniciar TDD**: debe entregar la propuesta y esperar la aprobación explícita del propietario del proyecto.

---

## 4. Gestión de Ambigüedades, Huecos y Contradicciones

Clasifica obligatoriamente todo hallazgo detectado durante el análisis SDD en una de estas **5 categorías operativas**:

| Categoría | Definición en ResourceHub | Acción Permitida al Agente | ¿Bloquea TDD? |
| :--- | :--- | :--- | :---: |
| **1. Dato conocido** | Definido explícitamente en un documento `APPROVED` de la jerarquía (Niveles 1–9). | Aplicarlo citando documento, sección e ID (`RF-*`, `RN-*`, `DTD-*`, `MDD-*`). | **No** |
| **2. Dato deducible** | Consecuencia lógica unívoca de reglas aprobadas (p. ej., firma XF en `B` derivada del contrato en `RH-ESP-INT-CON-v1.0`). | Aplicarlo dejando explícita la deducción en las Secciones 6–11 de la `SPEC-*`. | **No** |
| **3. Hipótesis razonable (o Defecto `MRD-007`)** | Detalle interno de implementación dentro de las 50 reglas XF, o aplicación de un valor por defecto aprobado en `MRD-007` para cuestiones `OPEN-*` de Nivel 2. | Documentarlo explícitamente en la Sección 15 de la `SPEC-*` como supuesto trazado y continuar. | **No** |
| **4. Recomendación técnica** | Propuesta ante un cambio necesario de esquema (`PCR-DAT-*`), nuevo código `FFMMCC` (`DTD-011`) o alternativa técnica con impacto inter-módulo. | Presentar opciones técnicas viables con la recomendación en primer lugar y el análisis de impacto; esperar decisión humana. | **Sí** |
| **5. Decisión pendiente (`BLOCKER` / `HIGH`)** | Hueco funcional no cubierto por `RN-*` ni por `MRD-007`, o contradicción entre documentos `APPROVED` de distinto nivel jerárquico. | Activar *Stop-the-Line*, registrar el bloqueo con severidad y fuentes consultadas, y solicitar aclaración al usuario. **Prohibido inventar la regla.** | **Sí** |

---

## 5. Gestión de Cambios — Protocolo *Spec-First* y Nuevos Códigos `DTD-011`

Cuando durante la codificación o las pruebas surja la necesidad de cambiar el comportamiento especificado:

1. **Principio *Spec-First* (Principio 8 de `RH-EST-SDD-v1.0`):** Detén la codificación en `resourcehub-tdd`, regresa a `resourcehub-sdd` y actualiza primero la ficha `SPEC-<MOD>-<NNN>` analizando el impacto sobre contratos, modelo físico (`34 tablas`) y pruebas de regresión.
2. **Flujo Normado de 4 Pasos para Nuevos Códigos de Error (`DTD-011`, `RH-FLU-SDD-TDD-v1.0 §2`):**
   Si una especificación necesita incorporar un nuevo código de error de negocio no presente entre los 34 códigos iniciales:
   - **Paso 1:** Especificar el nuevo valor simbólico en la librería común `MOD-11` (`ErrorCode` enum, bajo su subgrupo de módulo `PLATFORM`, `ACCESS`, `CATALOG`, `AVAILABILITY`, `BOOKING`, `POLICY`, `EVENTS`, `INTEGRATIONS`, `PAYMENTS` o familia `COMMON`).
   - **Paso 2:** Especificar la migración aditiva Flyway sobre `resourcehub_platform_db` insertando la fila en `platform_catalog.error_catalog` con su `numeric_value` posicional `FFMMCC` único, `family_code`, `module_code`, `http_status` y `default_message` seguro para cliente.
   - **Paso 3:** Especificar en la Sección 14 de la `SPEC-*` la verificación en fase `RED -> GREEN` mediante `PRU-03` (sincronización 1:1 `ErrorCode <-> platform_catalog.error_catalog`) y mediante `PRU-04` (traducción en `COMP-GW` al `ResponseWrapper` KO esperado).
   - **Paso 4:** Preparar la actualización de la tabla de códigos en `ResourceHub — Especificación de Interfaces y Contratos` y solicitar aprobación humana antes de consolidar.

---

## 6. Formato de Salida Esperado de `resourcehub-sdd`

Toda ejecución de `resourcehub-sdd` debe concluir generando un informe estructurado con este formato:

```markdown
## Informe Operativo SDD — `SPEC-<MOD>-<NNN>` (`<TT-* / HU-* / FUN-*>`)

### 1. Paquete de Contexto y Fuentes Consultadas
- Módulo / Esquema: `<MOD-XX> (<esquema>)`
- Documentos de la jerarquía consultados: `[Lista acotada de fuentes APPROVED]`

### 2. Ficha de Especificación `SPEC-<MOD>-<NNN>` (15 Secciones Oficiales)
- [Desarrollo o validación completa de las Secciones 1 a 15 según `references/spec-template-and-checklist.md`]

### 3. Clasificación de Hallazgos (Conocido / Deducible / Hipótesis MRD-007 / Recomendación / Decisión Pendiente)
- [Tabla de hallazgos y su tratamiento]

### 4. Dictamen de Quality Gates SDD (`QG-01` y `QG-02`)
- `QG-01` (0 bloqueos `BLOCKER`/`HIGH` + Entry Gate `MRD-007`): `PASS` | `FAIL`
- `QG-02` (Conformidad 50 reglas XF + 8 RH, `ResponseWrapper<T>`, `ErrorCode -> FFMMCC`, `0 PII`, 100% `RN`/`CU`): `PASS` | `FAIL`
- Estado resultante para `resourcehub-tdd`: `READY_FOR_DEVELOPMENT` | `STOP-THE-LINE (Requiere Aprobación Humana)`
```

---

## 7. Versionado y Mantenimiento de la SKILL

- **Versión actual:** `v1.0.0` (sincronizada con `RH-EST-SDD-v1.0`, `RH-DIS-TEC-v1.0`, `RH-ESP-INT-CON-v1.0` y `RH-CAT-ESP-IMP-v1.0`).
- **Regla de evolución (`DTD-015`):** Cualquier nueva versión aprobada del `Estándar de Desarrollo SDD` o de las decisiones técnicas `DTD-*` obliga a actualizar esta SKILL y su recurso [`references/spec-template-and-checklist.md`](references/spec-template-and-checklist.md) en la misma entrega documental.
