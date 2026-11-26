---
name: response-wrapper
description: >-
  Hace que todas las respuestas de una API (éxito y error) usen el modelo
  genérico ResponseWrapper { code, requestId, timestamp, description?, data?,
  errors? }, sea cual sea el lenguaje o framework. Usar cuando el usuario pida
  crear endpoints o APIs nuevas, unificar o normalizar las respuestas de una API
  existente, añadir manejo global de errores, trazabilidad de peticiones o
  revisar si una API cumple el contrato ResponseWrapper.
---

# ResponseWrapper: contrato de respuesta genérico

Todos los endpoints de la API devuelven **siempre** el mismo sobre, tanto cuando la petición va bien (OK) como cuando falla (KO).

## Contrato (cerrado)

### Campos siempre presentes

| Campo | Tipo | Significado |
|-------|------|-------------|
| `code` | integer | Código interno de negocio asociado a la respuesta. **No** es el status HTTP. |
| `requestId` | string | Identificador único de la petición, el mismo que se escribe en los logs del servidor. |
| `timestamp` | string (ISO-8601 UTC) | Momento en que se generó la respuesta, p. ej. `2026-11-23T18:31:00.123Z`. |

### Campos condicionales: OK y KO son excluyentes

| Campo | Tipo | Respuesta OK | Respuesta KO |
|-------|------|:-:|:-:|
| `description` | string | **Obligatorio** | Ausente |
| `data` | genérico `T` | Presente si la operación devuelve datos. Se omite si no devuelve nada. | Ausente |
| `errors` | lista de `ErrorDetail` (al menos 1 elemento) | Ausente | **Obligatorio** |

`ErrorDetail` = `{ field?: string, message: string }`
- `message`: texto del error, legible y seguro para mostrar al cliente.
- `field`: campo de la petición que provocó el error, por ejemplo `email` o `address.zip`. Se omite si el error no corresponde a un campo concreto.

Se considera **KO** toda respuesta de error: la controlada, cuando falla una validación o una regla de negocio, y la no controlada, cuando ocurre una excepción inesperada.

```json
// OK con datos
{ "code": 0, "requestId": "7f3c9a1e-...", "timestamp": "2026-11-23T18:31:00.123Z",
  "description": "OK", "data": { "id": 42, "name": "Ana" } }

// OK sin datos
{ "code": 0, "requestId": "a12b...", "timestamp": "2026-11-23T18:31:01.004Z",
  "description": "Usuario eliminado" }

// KO controlado (validación)
{ "code": 4000, "requestId": "c55d...", "timestamp": "2026-11-23T18:31:02.310Z",
  "errors": [ { "field": "email", "message": "Formato de email no válido" },
              { "field": "age", "message": "Debe ser mayor o igual a 18" } ] }

// KO de negocio / no controlado
{ "code": 4004, "requestId": "e9f0...", "timestamp": "2026-11-23T18:31:03.871Z",
  "errors": [ { "message": "Usuario no encontrado" } ] }
```

### Reglas del contrato
- El nombre del tipo es exactamente `ResponseWrapper` y el del elemento de error `ErrorDetail`. Los nombres en el JSON son exactamente `code`, `requestId`, `timestamp`, `description`, `data`, `errors`, `field` y `message`. No traducirlos y no dejar que la estrategia de nombres global del serializador (p. ej. `snake_case`) los cambie.
- Los campos condicionales que no aplican **se omiten del JSON**: no se envían como `null` ni como lista vacía. Configurar la omisión de nulos (`@JsonInclude(NON_NULL)`, `omitempty`, `exclude_none`, `JsonIgnoreCondition.WhenWritingNull`...) **solo en estos tipos**.
- Nunca mezclar los dos casos: una respuesta con `errors` no lleva ni `description` ni `data`, y al revés.
- Usar genéricos para `data` cuando el lenguaje los soporte (`ResponseWrapper<T>`). Si no los hay, usar el tipo dinámico del lenguaje.
- **No añadir más campos** sin preguntar antes al usuario y explicarle por qué hacen falta. Lo que sea específico de un caso va dentro de `data`.

## Pasos

1. **Inspeccionar el proyecto**: lenguaje, framework HTTP, serializador, logger y cómo devuelve hoy las respuestas y los errores. Buscar si ya existen un `ResponseWrapper`, un catálogo de códigos o un mecanismo de request id/correlation id, y reutilizarlos antes de crear nada.
2. **Crear `ResponseWrapper` y `ErrorDetail`** una sola vez, en el módulo de modelos/DTO compartido del proyecto (si el proyecto usa la arquitectura XF: `src/api/structs/`). Exponer solo factorías, para que nadie pueda construir un caso inválido:
   - `ok(description, data?)` → caso OK.
   - `error(code, errors)` → caso KO. Si `errors` llega vacío, se rechaza.
   - Las dos rellenan `requestId` y `timestamp` automáticamente desde el contexto de la petición. Ningún handler los asigna a mano.
3. **Request ID**: un middleware o filtro al principio de la cadena:
   - Si la petición trae la cabecera `X-Request-Id`, aceptarla solo si es segura: un máximo de 128 caracteres y únicamente `[A-Za-z0-9._-]`. Si no cumple o no viene, generar un UUID.
   - Guardarlo en el contexto de la petición (MDC, `AsyncLocalStorage`, `contextvars`, `context.Context`, `HttpContext.TraceIdentifier`...) para que lo usen las factorías y todas las líneas de log.
   - Devolverlo también en la cabecera de respuesta `X-Request-Id`.
   - **Correlación unificada:** usar `X-Request-Id` (`ResponseWrapper.requestId`) como identificador único de correlación end-to-end hacia logs, auditoría, eventos Outbox y trazas distribuidas (p. ej. OpenTelemetry), evitando duplicar cabeceras de correlación paralelas (`X-Correlation-Id`).
4. **Timestamp**: generarlo en el momento de construir la respuesta, siempre en UTC, en ISO-8601 y con milisegundos. Usar el reloj del sistema a través de la abstracción de reloj del proyecto, si existe (facilita los tests).
5. **Centralizar los códigos** en un catálogo único (nunca escribir números mágicos en los endpoints). Se admiten dos modalidades equivalentes según la arquitectura del proyecto:
   - **Catálogo directo en código:** un único enum/constantes con su `code` entero y su `description` o `message` por defecto (si el proyecto no define convención, proponer `0` = éxito y rangos por familia de error).
   - **Catálogo híbrido desacoplado (ENUM compartido + tabla CORE en BD cacheada por Gateway):** una librería común expone el `ErrorCode` simbólico tipado (dividido en familias transversales comunes + subgrupos independientes por módulo) y los módulos/microservicios **solo emiten el ENUM simbólico sin conocer los valores numéricos**; una tabla maestra en BD (p. ej. `platform_catalog.error_catalog`) almacena la correspondencia `code` → `numeric_value` posicional (p. ej. `FFMMCC` de 6 dígitos) y `default_message`, y **únicamente el API Gateway** lee y cachea dicha tabla (con fallback estático de emergencia para errores `500`/`503` si cae la BD) para traducir al entero exterior `ResponseWrapper.code`. Un test de integración en CI debe verificar la biyección 1:1 entre el ENUM y la tabla.
6. **Envolver todas las respuestas OK**: cada handler/controlador devuelve `ResponseWrapper<T>` creado con `ok(...)`. Si se usa un mecanismo transversal (interceptor, `ResponseBodyAdvice`, middleware), no debe volver a envolver lo que ya es un `ResponseWrapper`.
7. **Envolver todos los KO con un manejador global** (`@ControllerAdvice`, middleware de errores, `exception_handler`, `IExceptionFilter`, `recover`...):
   - Errores de validación → un `ErrorDetail` por cada campo que falla, con su `field`.
   - Excepciones de dominio conocidas → `code` del catálogo y `ErrorDetail` sin `field`.
   - Errores que genera el propio framework → también con el wrapper: body malformado (400), ruta inexistente (404), método no permitido (405), media type no soportado (415), autenticación/autorización (401/403).
   - Errores no controlados → código interno genérico y un único `ErrorDetail` con un mensaje genérico. **Nunca** exponer stack traces, mensajes de excepción internos, SQL ni rutas de ficheros. Registrar el detalle en el log junto con el `requestId`.
   - **Modo Gateway / Doble Borde (opcional cuando existe un API Gateway delante de módulos o microservicios):** los servicios/módulos internos pueden emitir errores detallados en `application/problem+json` (RFC 9457) incluyendo el `ErrorCode` simbólico, reservando ese formato exclusivamente al perímetro interno de confianza; el **API Gateway** intercepta dichas respuestas KO, elimina cualquier cabecera de diagnóstico interno en peticiones externas, resuelve el código numérico y mensaje seguro desde su caché del catálogo y devuelve siempre **`ResponseWrapper` KO** saneado al cliente exterior (sin volver a envolver respuestas OK que ya vengan en `ResponseWrapper<T>`).
8. **Status HTTP**: mantener el status HTTP semántico (200, 201, 400, 404, 500...) junto con el `code` interno, salvo que el proyecto o el usuario fijen otra política. Si el proyecto ya devuelve status, no cambiarlo sin preguntar, porque rompe a los clientes.
9. **Documentar el contrato** en la especificación de la API si existe (OpenAPI/Swagger...): esquemas `ResponseWrapper` y `ErrorDetail` reutilizables, `data` parametrizado por endpoint y respuestas de error documentadas con `errors`. Dejar claro qué campos son obligatorios y cuáles condicionales.
10. **Verificar** (ver abajo).

## Excepciones permitidas (no envolver)

Solo cuando el formato lo impide o el protocolo lo exige. Dejarlo indicado con un comentario en el código. Aun así, incluir la cabecera `X-Request-Id` siempre que sea posible.
- Descargas binarias, ficheros y streaming (`application/octet-stream`, SSE, websockets, chunked).
- Respuestas sin cuerpo por especificación (`204 No Content`, `304`, redirecciones `3xx`, `HEAD`).
- Endpoints que consume infraestructura y exigen un formato fijo (health checks/probes, métricas Prometheus, webhooks con contrato del proveedor) si el usuario no indica lo contrario.

> Si se usaba `204` para operaciones sin retorno, cambiar a `200` con un OK sin `data` solo si el usuario lo confirma, porque cambia el contrato.

## Formas idiomáticas por lenguaje

```java
// Java (Jackson)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseWrapper<T> {
    private int code;
    private String requestId;
    private Instant timestamp;          // serializar como ISO-8601 (WRITE_DATES_AS_TIMESTAMPS = false)
    private String description;         // solo OK
    private T data;                     // solo OK, opcional
    private List<ErrorDetail> errors;   // solo KO, no vacío
    // constructor privado + getters + ok(description, data) / error(code, errors)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String field, String message) {}
```

```kotlin
// Kotlin
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResponseWrapper<T> private constructor(
    val code: Int, val requestId: String, val timestamp: Instant,
    val description: String? = null, val data: T? = null, val errors: List<ErrorDetail>? = null,
)
data class ErrorDetail(val field: String? = null, val message: String)
```

```typescript
// TypeScript: unión discriminada para que el compilador impida mezclar los casos
interface ResponseBase { code: number; requestId: string; timestamp: string; }
export interface ErrorDetail { field?: string; message: string; }
export type ResponseWrapper<T> =
  | (ResponseBase & { description: string; data?: T; errors?: never })
  | (ResponseBase & { errors: [ErrorDetail, ...ErrorDetail[]]; description?: never; data?: never });
```

```python
# Python (pydantic): serializar con model_dump(exclude_none=True)
class ErrorDetail(BaseModel):
    field: str | None = None
    message: str

class ResponseWrapper(BaseModel, Generic[T]):
    code: int
    requestId: str
    timestamp: datetime                     # UTC
    description: str | None = None          # solo OK
    data: T | None = None                   # solo OK
    errors: list[ErrorDetail] | None = None # solo KO
```

```go
// Go
type ErrorDetail struct {
    Field   string `json:"field,omitempty"`
    Message string `json:"message"`
}

type ResponseWrapper[T any] struct {
    Code        int           `json:"code"`
    RequestID   string        `json:"requestId"`
    Timestamp   time.Time     `json:"timestamp"` // UTC; RFC3339
    Description string        `json:"description,omitempty"`
    Data        *T            `json:"data,omitempty"`
    Errors      []ErrorDetail `json:"errors,omitempty"`
}
```

```csharp
// C# (System.Text.Json)
public record ErrorDetail(
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Field,
    string Message);

public record ResponseWrapper<T>(
    int Code, string RequestId, DateTimeOffset Timestamp,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Description,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] T? Data,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] IReadOnlyList<ErrorDetail>? Errors);
// Comprobar que la política de nombres produce camelCase ("requestId")
```

En los lenguajes que no aparecen aquí, usar la estructura de datos idiomática con los mismos campos, los mismos nombres en el JSON y la misma omisión de los campos condicionales. Si el sistema de tipos lo permite (uniones, sealed classes, enums con datos), modelar OK/KO como variantes excluyentes.

## Verificación

- Hay un test (o un script de comprobación) que hace como mínimo estas peticiones y valida:
  - **En todas**: `code` es entero; `requestId` no está vacío y coincide con la cabecera `X-Request-Id`; `timestamp` es ISO-8601 UTC válido.
  1. OK con datos → tiene `description` y `data`, y no tiene `errors`.
  2. OK sin datos → tiene `description`, y no tiene ni `data` ni `errors`.
  3. Body inválido → `errors` con al menos un `field`, y sin `description` ni `data`.
  4. Error de dominio (p. ej. no encontrado) → `errors` no vacío, sin `description` ni `data`.
  5. Ruta inexistente → KO con el wrapper.
  6. Error no controlado → KO con mensaje genérico, sin detalles internos, y su `requestId` aparece en el log.
  7. `X-Request-Id` entrante válido → se respeta. Si es inválido o demasiado largo → se sustituye por uno generado.
- `grep` en los handlers: ningún endpoint devuelve un tipo distinto de `ResponseWrapper`, salvo las excepciones permitidas y comentadas. Nadie asigna `requestId` ni `timestamp` a mano.
- No hay códigos numéricos escritos a mano fuera del catálogo (en Modo Gateway, los módulos internos solo emiten `ErrorCode` simbólico y nunca números `FFMMCC` directos; y cualquier cabecera interna de diagnóstico RFC 9457 enviada desde el exterior es eliminada por el Gateway).
- No hay respuestas doblemente envueltas ni respuestas que mezclen campos de OK y de KO.
