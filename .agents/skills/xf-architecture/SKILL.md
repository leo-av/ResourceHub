---
name: xf-architecture
description: >-
  Aplica, migra o audita la Arquitectura de Software Cross-Framework (XF) en
  cualquier proyecto, independientemente del lenguaje, framework o entorno de
  ejecución (backend, frontend, móvil, CLI, IoT o embebido). Usar cuando el
  usuario pida estructurar un proyecto nuevo bajo XF, refactorizar un proyecto
  existente a XF, añadir componentes conformes a XF o validar la conformidad
  arquitectónica XF.
---

# Arquitectura XF (Cross-Framework)

XF especializa la Capa de Aplicación (Nivel 7) del modelo OSI en **3 capas cerradas** y **5 tipos de componentes cerrados**. La herramienta/framework implementa XF —nunca al revés— encapsulando dependencias propietarias dentro de componentes XF.

## Flujo de aplicación (cualquier tecnología)

1. **Detectar stack y mapear primitivas del lenguaje**:
   - Identificar la carpeta raíz de código fuente (`src/`, `lib/`, `app/`, `internal/`, etc.; se refiere aquí como `src/`) y el punto de entrada real (`main`, `App`, `index`, `Program`, etc.).
   - Definir los equivalentes idiomáticos del lenguaje para:
     - **Clases/módulos estáticos no instanciables** (`R`, `B`, `A`, `XF`, `*Utils`).
     - **Herencia o composición base** (`base/`: herencia en OOP; *embedding*/composición/*traits* en lenguajes sin herencia de clases como Go o Rust).
     - **Estructuras de datos puras y excepciones** (`structs/`: `struct`/`record`/`dataclass`/`interface`/`type` + errores tipados).
2. **Crear o adaptar el proyecto zigoto** con las 3 capas obligatorias (`api`, `business`, `repository`) y, opcionalmente, el componente de arquitectura `XF` en `src/`.
3. **Diseñar el mapa de componentes antes de codificar**:
   - **Acceso (`repository`)**: 1 `*Repository` por cada conjunto lógico de datos de una aplicación/fuente externa o recurso del SO. Factorizar protocolos compartidos en `repository/base/`.
   - **Negocio (`business`)**: 1 y solo 1 `*Business` por cada concepto de la ontología del dominio (`instance/`) o capacidad del dispositivo/periférico (`device/`).
   - **Interacción (`api`)**: 1 `*Service` por recurso/grupo de endpoints sistémicos (`services/`) y/o 1 `*View` por pantalla o unidad gráfica (`gui/`).
   - **Transferencia (`structs`)**: 1 sola estructura por concepto de dominio (evitar duplicar Entity/Model/DTO/State) colocada en la capa más baja que la necesita.
4. **Implementar de abajo hacia arriba**: `repository` (7.1) → `business` (7.2) → `api` (7.3).
   - Si el proyecto es existente/tradicional, encapsular las dependencias o módulos legados dentro de `base/` o `logic/` de su capa correspondiente sin reescribir librerías externas.
5. **Cablear inyección y ciclo de vida**:
   - Registrar los singletons en `R`, `B` y `A`.
   - Implementar `init()` (en orden inverso al grafo de dependencias entre pares) y `terminate()` (liberación de conexiones/recursos) en `R`, `B`, `A`.
   - Encapsular en `src/XF` (`R.init()` → `B.init()` → `A.init()`; y `terminate()` en cierre/error fatal) e invocar desde el punto de entrada de la aplicación.
6. **Auditar conformidad**: recorrer la lista de 50 reglas al final de este documento y corregir toda violación `Obligatorio`.

---

## Estructura canónica (Proyecto Zigoto)

```text
/<projectName>
└─ /src
   ├─ /api                 # Capa de Interacción (Nivel 7.3)
   │  ├─ /base             # Tipo 2: Generalización (RestService, FormalService, StatefulView...)
   │  ├─ /logic            # Tipo 1: Lógicos (*Service, *View)
   │  │  ├─ /services      # (Opcional) Interacciones sistémicas (*Service)
   │  │  └─ /gui           # (Opcional) Interacciones gráficas (*View; subdivisible libremente)
   │  ├─ /structs          # Tipo 5: Estructuras (sin sufijo) y errores (*Exception)
   │  ├─ /utils            # Tipo 4: Utilidades puras (*Utils)
   │  └─ A                 # Tipo 3: Inyección de Interacción (Singleton Gathering)
   ├─ /business            # Capa de Negocio (Nivel 7.2)
   │  ├─ /base             # Tipo 2: Generalización (StatefulBusiness, BroadcastBusiness, ScheduledBusiness...)
   │  ├─ /logic            # Tipo 1: Lógicos (*Business)
   │  │  ├─ /instance      # (Opcional) Lógica de dominio de la solución
   │  │  └─ /device        # (Opcional) Gestión del dispositivo y periféricos
   │  ├─ /structs          # Tipo 5: Estructuras y excepciones
   │  ├─ /utils            # Tipo 4: Utilidades puras (*Utils)
   │  └─ B                 # Tipo 3: Inyección de Negocio
   ├─ /repository          # Capa de Acceso (Nivel 7.1)
   │  ├─ /base             # Tipo 2: Generalización (RestRepository, PostgresRepository...)
   │  ├─ /logic            # Tipo 1: Lógicos (*Repository)
   │  │  ├─ /local         # (Opcional) Recursos en la misma máquina (FS, IPC, SO)
   │  │  └─ /remote        # (Opcional) Aplicaciones externas (HTTP, DB remota, sockets, serie)
   │  ├─ /structs          # Tipo 5: Estructuras y excepciones
   │  ├─ /utils            # Tipo 4: Utilidades puras (*Utils)
   │  └─ R                 # Tipo 3: Inyección de Acceso
   └─ XF                   # (Opcional) Componente de Arquitectura (init/terminate global)
```

> Las 3 carpetas de capa (`api`, `business`, `repository`) son obligatorias y cerradas: no crear carpetas de capa adicionales ni archivos huérfanos en `src/`. Dentro de cada capa, las subcarpetas `base/`, `utils/` y `structs/` pueden omitirse solo si no existen componentes de ese tipo aún.

---

## Matriz de componentes (5 tipos cerrados)

Todo componente debe clasificarse en **1 capa** y **1 solo tipo**. Si un archivo mezcla tipos, dividirlo.

| # | Tipo | Carpeta | Nomenclatura | Estado en memoria | Instanciación / Herencia | Aislamiento entre capas |
|---|------|---------|--------------|-------------------|--------------------------|-------------------------|
| 1 | **Lógico** | `logic/` | `*Repository`, `*Business`, `*Service`, `*View` | **Sí** (único tipo autorizado a mantener estado de dominio) | Singleton instanciado **solo** en `R`/`B`/`A` (salvo sub-vistas compuestas dentro de `render()` en UI declarativa) | Solo hereda de `base/` de su **misma** capa |
| 2 | **Generalización** | `base/` | Descriptiva del patrón + sufijo de capa (`RestRepository`, `StatefulBusiness`, `StatefulView`...) | Solo soporte heredado por `logic` | Abstracto / no resuelve lógica particular por sí solo | **Estricto**: nunca abstrae ni es heredado desde otra capa (duplicar si hace falta) |
| 3 | **Inyección** | Raíz de capa | Exactamente `R`, `B`, `A` (1 por capa) | No muta estado propio; expone referencias `static readonly` | No instanciable; contiene solo definición + `init()` + `terminate()` | Solo consumido desde su misma capa o capas **superiores** (`<capa>.<comp>.<op>`) |
| 4 | **Utilidad** | `utils/` | Sufijo obligatorio `*Utils` | **No** (funciones puras, sin efectos laterales) | No instanciable, no heredable | **Estricto**: nunca usado desde otra capa |
| 5 | **Transferencia** | `structs/` (o interno al componente lógico si es privado) | Datos: **sin sufijo** (`User`, `Temperature`). Errores: **`*Exception`** | Solo atributos del dato; sin lógica ni acoplamiento al framework | Estructuras de datos puras | **Excepción controlada**: usable en la **misma capa o capas superiores**, nunca hacia abajo |

---

## Reglas por capa

### 1. Capa de Acceso — `repository` (Nivel 7.1)

Abstrae protocolos de comunicación y acceso a datos de otras aplicaciones sobre la Capa de Presentación (Nivel 6).

- **5 etapas invariantes** que resuelve cada acceso y política de errores:
  1. **Protocolo de acceso** (establecer canal/conexión/cliente): fallo de implementación → **no** propagar tipificado.
  2. **Contenido enviado** (serialización, cabeceras, query, bytes): fallo de implementación → **no** propagar tipificado.
  3. **Respuesta de comunicación** (timeout, red caída, HTTP != 200, permisos, recurso ocupado): fallo de contexto → **propagar tipificado** (`*Exception`).
  4. **Contenido de respuesta** (deserializar stream a `struct`): fallo remoto/protocolo → **propagar tipificado**.
  5. **Semántica de respuesta** (campos obligatorios, tipos, rangos, códigos de envoltura): fallo remoto/dominio → **propagar tipificado**.
- **Remoto vs Local**: `remote/` prioriza control de conectividad/desconexión; `local/` prioriza disponibilidad y bloqueo de recursos de máquina.
- Nunca contiene reglas de negocio ni presentación.

### 2. Capa de Negocio — `business` (Nivel 7.2)

Resuelve reglas, transformaciones, casos de uso, consistencia y estado de la ontología del artefacto.

- **1 concepto de ontología = 1 `*Business`** (evitar dependencias circulares entre negocios).
- **Instancia vs Dispositivo**:
  - `instance/`: reglas del dominio e integridad semántica de las estructuras de negocio (reutilizable entre plataformas del mismo dominio).
  - `device/`: capacidades y periféricos del hardware/entorno (red, idioma, almacenamiento seguro, sensores, permisos; reutilizable entre dominios del mismo dispositivo).
- **Anatomía de un `*Business`**: operaciones (métodos públicos) compuestas por sentencias secuenciales precedidas por **condiciones de negocio** (precondiciones/guardas que lanzan `*Exception` si no se satisfacen).
- Invoca accesos exclusivamente vía `R.<repo>.<op>()` y negocios pares vía `B.<negocio>.<op>()`. Nunca conoce `A`.

### 3. Capa de Interacción — `api` (Nivel 7.3)

Define los puntos de entrada que desencadenan operaciones de `B` y formatean/presentan resultados y errores.

- **Interacción sistémica (`*Service`)**: define protocolo, ruta, método, validaciones sintácticas/semánticas de entrada, serialización y códigos de respuesta/instancia, latencia/trazado; delega ejecución a `B.<negocio>.<op>()`.
- **Interacción gráfica (`*View`)**: debe encapsular de forma independiente **exclusivamente 3 responsabilidades**:
  1. **Composición**: jerarquía declarativa de elementos UI.
  2. **Estilo**: aspecto visual definido por componente (evitar hojas de estilo globales sin estructura).
  3. **Lógica de presentación**: mínima imprescindible para renderizado y eventos (`loading`, `emptyState`, `onClick`, `popUp`, `onCreate`, `onPause`), delegando toda lógica de negocio a `B`.
- Nunca accede directamente a protocolos externos ni contiene lógica de negocio.

---

## Flujos de datos y dependencias permitidas

- **Estructura única por concepto**: unificar lo que otras arquitecturas separan en `Entity`/`Model`/`DTO`/`VO`/`State` en un único `struct`, transformado por los componentes lógicos según su etapa.
- **Dos canales equivalentes**: flujo principal (retornos con `struct` sin sufijo) y flujo de excepción (errores con `*Exception`). Ambos son componentes de transferencia en `structs/`.
- **Direccionalidad de referencias**:

| Desde capa | Puede usar `R` | Puede usar `B` | Puede usar `A` | Puede usar `structs` de | Puede usar `base` / `utils` de |
|------------|:-:|:-:|:-:|:-:|:-:|
| `api` (7.3) | **No** (delegar siempre vía `B`) | **Sí** | **Sí** (pares) | `api`, `business`, `repository` | Solo `api` |
| `business` (7.2) | **Sí** | **Sí** (pares) | **No** | `business`, `repository` | Solo `business` |
| `repository` (7.1) | **Sí** (pares) | **No** | **No** | Solo `repository` | Solo `repository` |

---

## Inicialización y terminación (`R`, `B`, `A`, `XF`)

1. Cada componente de inyección (`R`, `B`, `A`) implementa:
   - **Definición**: instancia única e inmutable de cada componente lógico publicado.
   - **`init()`**: inicializa los componentes lógicos de la capa en **orden inverso a su grafo de dependencias entre pares**. Si falla un requisito mínimo, abortar arranque.
   - **`terminate()`**: libera conexiones, hilos, timers y recursos en el cierre o ante excepción no controlada.
2. **Orden entre capas** en `XF.init()` (o directamente en el punto de entrada):
   ```text
   R.init()  →  B.init()  →  A.init()
   ```
   En `XF.terminate()`: liberar en orden controlado (recomendado inverso: `A.terminate()` → `B.terminate()` → `R.terminate()`).
3. El punto de entrada del framework (`main`, `App`, etc.) solo dispara `XF.init()` (o `R`/`B`/`A.init()`) y enlaza `XF.terminate()` al cierre del proceso.

---

## Equivalencias tecnológicas e integración

### Traducción de términos de frameworks a capas XF

| Capa XF | Conceptos equivalentes en frameworks tradicionales |
|---------|-----------------------------------------------------|
| **`repository`** (Acceso) | Repository (Spring), Entity (Hibernate), DTO/Model de persistencia, API client, Client, Socket, DAO, QuerySet (Django), Intent (Android) |
| **`business`** (Negocio) | Service de dominio (Spring/Android), Bean/DI/Autowired/Provider, Middleware, Serializer (Django), Store (Vuex), Slice (Redux), Hook, Bloc (Flutter), Task, Scheduled, Route/Navigation, Interceptor, LiveData, Decorator, Job, Channel, Async, Broadcast |
| **`api`** (Interacción) | Controller (Spring), Router (Node), View/Template (Django), Form, Directive/Pipe (Angular), Saga (Redux), Widget (Flutter), ViewModel/Activity/Fragment/DataBinding (Android), Composable (Jetpack) |

### Patrones de integración (4 escenarios)

1. **XF integra XF**: re-exportar por transitividad las instancias necesarias en `R`/`B`/`A` e invocar los `init()`/`terminate()` del artefacto XF importado.
2. **XF integra artefacto/librería tradicional**: envolver la librería externa dentro de un componente `base/` o `logic/` de la capa adecuada (`dependency-wrapping`).
3. **Artefacto tradicional integra XF**: importar `R`, `B`, `A` o `XF` del artefacto XF e inicializar/invocar sus operaciones directamente.
4. **Aplicación ↔ Aplicación (procesos distintos)**: comunicar vía protocolos estándar OSI (HTTP, gRPC, SQL, IPC, serie), modelando el consumo en `repository` y la exposición en `api`.

---

## Auditoría de conformidad XF (50 reglas)

Al finalizar una implementación, migración o revisión, verificar estas 50 reglas. Cualquier fallo `Obligatorio` impide declarar conformidad XF.

### Estructura y tipificación
- `no-api-folder` *(Obligatorio)*: existe `src/api`.
- `no-business-folder` *(Obligatorio)*: existe `src/business`.
- `no-repository-folder` *(Obligatorio)*: existe `src/repository`.
- `unknown-folder` *(Obligatorio)*: no existen carpetas en `src/` que no correspondan a una capa XF.
- `folder-mismatch` *(Obligatorio)*: la estructura interna de cada capa usa solo `base`, `logic`, `structs`, `utils` y su componente de inyección (más subagrupaciones válidas dentro de `logic`).
- `component-untyped` *(Obligatorio)*: ningún componente queda fuera de las carpetas XF.
- `component-mismatch` *(Obligatorio)*: el tipo del componente coincide con la capa y carpeta donde reside.

### Componentes lógicos por capa
- `api-folder` *(Obligatorio)*: todo componente de interacción está dentro de `api/`.
- `service-naming` *(Obligatorio)*: toda interacción sistémica termina en `Service`.
- `service-mismatch` *(Obligatorio)*: un `*Service` contiene solo lógica de servicio/protocolo de interacción.
- `view-naming` *(Obligatorio)*: toda interacción gráfica termina en `View`.
- `view-mismatch` *(Obligatorio)*: una `*View` contiene solo composición, estilo y lógica de presentación.
- `business-folder` *(Obligatorio)*: todo componente de negocio está dentro de `business/`.
- `business-naming` *(Obligatorio)*: todo componente lógico de negocio termina en `Business`.
- `business-mismatch` *(Obligatorio)*: un `*Business` contiene solo lógica de negocio.
- `repository-folder` *(Obligatorio)*: todo componente de acceso está dentro de `repository/`.
- `repository-naming` *(Obligatorio)*: todo componente lógico de acceso termina en `Repository`.
- `repository-mismatch` *(Obligatorio)*: un `*Repository` contiene solo lógica de acceso.

### Utilidades y generalización
- `util-folder` *(Obligatorio)*: toda utilidad está en `<capa>/utils/`.
- `util-logic` *(Obligatorio)*: contiene solo funciones puras de utilidad (sin estado ni lógica de dominio).
- `util-naming` *(Obligatorio)*: termina en `Utils`.
- `util-layering` *(Obligatorio)*: nunca se importa un `*Utils` desde otra capa.
- `base-folder` *(Obligatorio)*: todo componente base está en `<capa>/base/`.
- `base-mismatch` *(Obligatorio)*: contiene solo generalización común (sin lógica particular de un caso).
- `logic-inheritance` *(Obligatorio)*: un componente lógico nunca hereda de un `base` de otra capa.

### Transferencia y excepciones
- `struct-folder` *(Obligatorio)*: toda estructura de transferencia general está en `<capa>/structs/` (o interna al componente lógico si es privada).
- `struct-logic` *(Obligatorio)*: contiene solo definición de datos (sin lógica de negocio/acceso/interacción).
- `struct-naming` *(Obligatorio)*: los modelos de datos no llevan sufijos extra (`DTO`, `Entity`, `Model`, etc.).
- `exception-naming` *(Obligatorio)*: las estructuras de error terminan en `Exception`.
- `struct-layering` *(Obligatorio)*: una estructura nunca se usa en una capa inferior a donde está definida.
- `struct-flow` *(Recomend.)*: datos normales viajan por flujo principal y errores por flujo de excepción.

### Inyección, instanciación y ciclo de vida
- `R-naming` / `R-folder` *(Obligatorio)*: inyección de Acceso llamada `R` y ubicada en `src/repository/R`.
- `B-naming` / `B-folder` *(Obligatorio)*: inyección de Negocio llamada `B` y ubicada en `src/business/B`.
- `A-naming` / `A-folder` *(Obligatorio)*: inyección de Interacción llamada `A` y ubicada en `src/api/A`.
- `injection-mismatch` *(Obligatorio)*: `R`, `B` y `A` contienen solo definición de singletons, `init()` y `terminate()`.
- `injection-layering` *(Obligatorio)*: ningún componente de inyección se usa en una capa inferior a la suya.
- `logic-injection` *(Obligatorio)*: un componente lógico no se instancia en el componente de inyección de otra capa.
- `logic-layering` *(Obligatorio)*: un componente lógico no se expone/usa a través del componente de inyección de otra capa.
- `logic-instantiation` *(Obligatorio)*: ningún componente lógico se instancia fuera de su componente de inyección (`R`, `B`, `A`).
- `logic-initialization` *(Recomend.)*: todo `init()` de un componente lógico es invocado desde su componente de inyección.
- `logic-termination` *(Recomend.)*: todo `terminate()` de un componente lógico es invocado desde su componente de inyección.

### Componente de arquitectura e integración
- `xf-naming` *(Obligatorio)*: si existe, se llama exactamente `XF`.
- `xf-folder` *(Obligatorio)*: ubicado en la raíz de fuentes (`src/XF`).
- `xf-mismatch` *(Obligatorio)*: `XF` solo encapsula inicialización y terminación de `R`, `B` y `A`.
- `xf-initialization` *(Recomend.)*: los componentes de inyección se inicializan en el arranque (`R` → `B` → `A`).
- `dependency-wrapping` *(Recomend.)*: toda dependencia externa no-XF está encapsulada en un componente XF.
- `dependency-injection` *(Recomend.)*: las dependencias XF externas se integran a través de `R`, `B` o `A`.
