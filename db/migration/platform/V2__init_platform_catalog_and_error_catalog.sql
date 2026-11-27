-- =============================================================================
-- ResourceHub — Migración Flyway V2 (Plano de Plataforma)
-- Archivo: db/migration/platform/V2__init_platform_catalog_and_error_catalog.sql
-- Propósito (`TT-003` / `SPEC-ORG-003` / `PCR-DAT-001` / `PCR-DAT-002`):
--   1. Crear la tabla maestra `platform_catalog.organization` (`EL-PLAT-01`).
--   2. Crear la tabla maestra `platform_catalog.error_catalog` (`EL-PLAT-05`,
--      con familia `60` `RATE_LIMITED` aprobada en `PCR-DAT-002` / `DEC-098`).
--   3. Sembrar de forma idempotente 1 organización inicial `ACTIVE` y los
--      34 códigos posicionales `FFMMCC` sincronizados 1:1 con `ErrorCode` (`MOD-11`).
-- =============================================================================

-- 1. Tabla `platform_catalog.organization` (EL-PLAT-01, 12 columnas)
CREATE TABLE IF NOT EXISTS platform_catalog.organization (
    id                     UUID         NOT NULL,
    slug                   VARCHAR(63)  NOT NULL,
    name                   VARCHAR(150) NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONING',
    initial_time_zone_id   VARCHAR(64)  NOT NULL,
    db_host                VARCHAR(255) NOT NULL,
    db_port                INTEGER      NOT NULL DEFAULT 5432,
    db_name                VARCHAR(63)  NOT NULL,
    current_schema_version VARCHAR(32)  NOT NULL DEFAULT '0',
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    archived_at            TIMESTAMPTZ  NULL,
    CONSTRAINT pk_organization PRIMARY KEY (id),
    CONSTRAINT uq_organization_slug UNIQUE (slug),
    CONSTRAINT uq_organization_db_name UNIQUE (db_name),
    CONSTRAINT chk_org_slug_format CHECK (slug ~ '^[a-z0-9-]+$'),
    CONSTRAINT chk_org_name_length CHECK (char_length(trim(name)) >= 2),
    CONSTRAINT chk_org_status CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT chk_org_tz_not_blank CHECK (char_length(trim(initial_time_zone_id)) > 0),
    CONSTRAINT chk_org_db_host_not_blank CHECK (char_length(trim(db_host)) > 0),
    CONSTRAINT chk_org_db_port CHECK (db_port BETWEEN 1 AND 65535),
    CONSTRAINT chk_org_db_name_not_blank CHECK (char_length(trim(db_name)) > 0),
    CONSTRAINT chk_org_schema_ver_not_blank CHECK (char_length(trim(current_schema_version)) > 0),
    CONSTRAINT chk_org_updated CHECK (updated_at >= created_at),
    CONSTRAINT chk_org_archived_at CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_organization_status
    ON platform_catalog.organization (status);

COMMENT ON TABLE platform_catalog.organization IS
    'EL-PLAT-01: Catálogo maestro de organizaciones clientes en el Plano de Plataforma (D-43, CIC-01).';

-- 2. Tabla `platform_catalog.error_catalog` (EL-PLAT-05, 10 columnas, PCR-DAT-001 + PCR-DAT-002)
CREATE TABLE IF NOT EXISTS platform_catalog.error_catalog (
    id              UUID         NOT NULL,
    code            VARCHAR(80)  NOT NULL,
    numeric_value   INTEGER      NOT NULL,
    family_code     SMALLINT     NOT NULL,
    module_code     SMALLINT     NOT NULL,
    http_status     SMALLINT     NOT NULL,
    default_message VARCHAR(255) NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_error_catalog PRIMARY KEY (id),
    CONSTRAINT uq_error_catalog_code UNIQUE (code),
    CONSTRAINT uq_error_catalog_numeric_value UNIQUE (numeric_value),
    CONSTRAINT chk_error_catalog_code_format CHECK (code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT chk_error_catalog_numeric_nonneg CHECK (numeric_value >= 0),
    CONSTRAINT chk_error_catalog_family CHECK (family_code IN (0, 10, 20, 30, 40, 50, 60, 90)),
    CONSTRAINT chk_error_catalog_module CHECK (module_code BETWEEN 0 AND 99),
    CONSTRAINT chk_error_catalog_http_status CHECK (http_status BETWEEN 200 AND 599),
    CONSTRAINT chk_error_catalog_message_not_blank CHECK (char_length(trim(default_message)) > 0),
    CONSTRAINT chk_error_catalog_updated CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_error_catalog_family
    ON platform_catalog.error_catalog (family_code);
CREATE INDEX IF NOT EXISTS idx_error_catalog_module
    ON platform_catalog.error_catalog (module_code);
CREATE INDEX IF NOT EXISTS idx_error_catalog_active
    ON platform_catalog.error_catalog (is_active);

COMMENT ON TABLE platform_catalog.error_catalog IS
    'EL-PLAT-05: Catálogo maestro CORE DTD-011 (ErrorCode <-> FFMMCC) cacheado exclusivamente en COMP-GW.';

-- 3. Semilla idempotente: 1 organización inicial ACTIVE
INSERT INTO platform_catalog.organization (
    id,
    slug,
    name,
    status,
    initial_time_zone_id,
    db_host,
    db_port,
    db_name,
    current_schema_version,
    created_at,
    updated_at,
    archived_at
) VALUES (
    '01936a00-0000-7000-8000-000000000001',
    'resourcehub-demo',
    'ResourceHub Demo Organization',
    'ACTIVE',
    'Europe/Madrid',
    'localhost',
    5432,
    'resourcehub',
    '2',
    TIMESTAMPTZ '2026-11-27T00:00:00Z',
    TIMESTAMPTZ '2026-11-27T00:00:00Z',
    NULL
) ON CONFLICT (slug) DO NOTHING;

-- 4. Semilla idempotente: los 34 códigos FFMMCC alineados 1:1 con ErrorCode (MOD-11)
INSERT INTO platform_catalog.error_catalog (
    id, code, numeric_value, family_code, module_code, http_status, default_message, is_active, created_at, updated_at
) VALUES
    ('01936a00-0001-7000-8000-000000000001', 'OK', 0, 0, 0, 200, 'OK', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000002', 'VALIDATION_FAILED', 100001, 10, 0, 400, 'Error de validación en los datos enviados', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000003', 'MALFORMED_REQUEST', 100002, 10, 0, 400, 'Formato de petición no válido', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000004', 'UNAUTHENTICATED', 200001, 20, 0, 401, 'Autenticación requerida o sesión expirada', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000005', 'INVALID_CREDENTIALS', 200002, 20, 0, 401, 'Credenciales no válidas', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000006', 'SCOPE_VIOLATION', 200003, 20, 0, 403, 'El ámbito operacional actual no tiene acceso a este recurso', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000007', 'NO_PERMISSION', 200004, 20, 0, 403, 'No tienes permisos para realizar esta operación', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000008', 'ORGANIZATION_NOT_ACTIVE', 200005, 20, 0, 403, 'La organización no se encuentra operativa', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000009', 'SECURITY_SIGNATURE_INVALID', 200006, 20, 0, 401, 'Firma criptográfica o marca temporal no válida', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000010', 'NOT_FOUND', 300001, 30, 0, 404, 'Recurso no encontrado', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000011', 'ALREADY_EXISTS', 400001, 40, 0, 409, 'El elemento ya existe', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000012', 'TRANSITION_NOT_ALLOWED', 400002, 40, 0, 409, 'El estado actual no permite realizar esta acción', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000013', 'AVAILABILITY_ACTIVE_RESERVATIONS_CONFLICT', 400401, 40, 4, 409, 'El bloqueo manual colisiona con reservas activas existentes', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000014', 'BOOKING_CONFLICT', 400501, 40, 5, 409, 'La franja seleccionada ya está ocupada', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000015', 'BOOKING_CAPACITY_EXCEEDED', 400502, 40, 5, 409, 'No quedan suficientes plazas disponibles para esa franja', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000016', 'EVENTS_IDEMPOTENCY_IN_PROGRESS', 400801, 40, 8, 409, 'La operación con esta clave de idempotencia sigue en curso', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000017', 'CATALOG_RESOURCE_NOT_BOOKABLE', 500301, 50, 3, 422, 'El recurso no está habilitado para reservas', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000018', 'CATALOG_HIERARCHY_DEPTH_EXCEEDED', 500302, 50, 3, 422, 'Se ha superado la profundidad máxima de jerarquía de recursos', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000019', 'CATALOG_HIERARCHY_CYCLE_DETECTED', 500303, 50, 3, 422, 'La relación jerárquica solicitada genera un ciclo inválido', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000020', 'AVAILABILITY_INVALID_INTERVAL', 500401, 50, 4, 422, 'El intervalo temporal no es válido', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000021', 'AVAILABILITY_OUTSIDE_HOURS', 500402, 50, 4, 422, 'La franja solicitada está fuera del horario de disponibilidad', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000022', 'AVAILABILITY_RESOURCE_BLOCKED', 500403, 50, 4, 422, 'El recurso se encuentra bloqueado en la franja seleccionada', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000023', 'BOOKING_SERIES_LIMIT_EXCEEDED', 500501, 50, 5, 422, 'La serie recurrente supera el horizonte o el número máximo de ocurrencias', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000024', 'BOOKING_WAITLIST_FULL', 500502, 50, 5, 422, 'La lista de espera ha alcanzado su límite máximo', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000025', 'BOOKING_WAITLIST_SLOT_AVAILABLE', 500503, 50, 5, 422, 'Existen plazas libres; puedes reservar directamente sin lista de espera', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000026', 'POLICY_WINDOW_VIOLATED', 500601, 50, 6, 422, 'La solicitud incumple la ventana de antelación configurada', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000027', 'POLICY_QUOTA_EXCEEDED', 500602, 50, 6, 422, 'Se ha superado el cupo máximo de reservas permitido', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000028', 'POLICY_CANCELLATION_WINDOW_EXPIRED', 500603, 50, 6, 422, 'Ha vencido el plazo permitido para cancelar esta reserva', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000029', 'POLICY_PENALTY_ACTIVE', 500604, 50, 6, 422, 'Existe una penalización activa que impide crear nuevas reservas', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000030', 'EVENTS_IDEMPOTENCY_CONFLICT', 500801, 50, 8, 422, 'La clave de idempotencia ya fue utilizada con una petición distinta', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000031', 'PAYMENTS_REFUND_EXCEEDS_CHARGED', 501001, 50, 10, 422, 'El importe a reembolsar supera el saldo cobrado de la reserva', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000032', 'RATE_LIMITED', 600001, 60, 0, 429, 'Demasiadas peticiones; inténtalo de nuevo más tarde', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000033', 'INTERNAL_ERROR', 900001, 90, 0, 500, 'Error interno del servidor', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z'),
    ('01936a00-0001-7000-8000-000000000034', 'SERVICE_UNAVAILABLE', 900002, 90, 0, 503, 'Servicio temporalmente no disponible', TRUE, TIMESTAMPTZ '2026-11-27T00:00:00Z', TIMESTAMPTZ '2026-11-27T00:00:00Z')
ON CONFLICT (code) DO NOTHING;
