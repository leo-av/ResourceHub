-- =============================================================================
-- ResourceHub — Migración Flyway V6 (Plano de Organización: reservations)
-- Archivo: db/migration/organization/V6__init_reservations_base.sql
-- Propósito (`TT-011` / `SPEC-RES-001`):
--   1. Crear `reservations.reservation` (`EL-RES-02`, 20 cols, núcleo ACID).
--   2. Crear `reservations.reservation_line` (`EL-RES-03`, 12 cols, GiST).
--   3. Declarar FK intra-esquema sin referencias inter-esquema (`ADR-007`).
-- =============================================================================

-- 1. Tabla `reservations.reservation` (EL-RES-02, 20 columnas)
CREATE TABLE IF NOT EXISTS reservations.reservation (
    id                            UUID         NOT NULL,
    requester_id                  UUID         NOT NULL,
    holder_id                     UUID         NOT NULL,
    origin_scope                  VARCHAR(25)  NOT NULL,
    status                        VARCHAR(15)  NOT NULL,
    pending_reason                VARCHAR(20)  NULL,
    expires_at                    TIMESTAMPTZ  NULL,
    series_id                     UUID         NULL,
    series_occurrence_index       INTEGER      NULL,
    total_amount_minor            BIGINT       NOT NULL DEFAULT 0,
    currency_code                 CHAR(3)      NOT NULL DEFAULT 'EUR',
    payment_status                VARCHAR(30)  NOT NULL DEFAULT 'SIN_CARGO',
    attendance_status             VARCHAR(20)  NULL,
    attendance_marked_by_user_id  UUID         NULL,
    attendance_marked_at          TIMESTAMPTZ  NULL,
    cancellation_source           VARCHAR(30)  NULL,
    caused_by_block_id            UUID         NULL,
    version                       INTEGER      NOT NULL DEFAULT 1,
    created_at                    TIMESTAMPTZ  NOT NULL,
    updated_at                    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_reservation PRIMARY KEY (id),
    CONSTRAINT chk_res_origin_scope CHECK (
        origin_scope IN (
            'AMB_01_CLIENT',
            'AMB_02_PROFESSIONAL',
            'AMB_03_MANAGEMENT',
            'EXTERNAL_CLIENT',
            'SYSTEM'
        )
    ),
    CONSTRAINT chk_res_status CHECK (
        status IN (
            'PENDING',
            'CONFIRMED',
            'CANCELLED',
            'REJECTED',
            'EXPIRED',
            'COMPLETED',
            'NO_SHOW'
        )
    ),
    CONSTRAINT chk_res_pending_reason CHECK (
        pending_reason IS NULL OR pending_reason IN ('APPROVAL', 'WAITLIST_OFFER', 'PAYMENT')
    ),
    CONSTRAINT chk_res_pending_expires CHECK (
        status <> 'PENDING' OR expires_at IS NOT NULL
    ),
    CONSTRAINT chk_res_total_amount CHECK (total_amount_minor >= 0),
    CONSTRAINT chk_res_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_res_payment_status CHECK (
        payment_status IN (
            'SIN_CARGO',
            'PENDIENTE_DE_PAGO',
            'PAGADA',
            'REEMBOLSADA_PARCIALMENTE',
            'REEMBOLSADA_TOTALMENTE'
        )
    ),
    CONSTRAINT chk_res_attendance_status CHECK (
        attendance_status IS NULL OR attendance_status IN ('PRESENT', 'ABSENT', 'AUTO_COMPLETED')
    ),
    CONSTRAINT chk_res_cancellation_source CHECK (
        cancellation_source IS NULL OR cancellation_source IN (
            'HOLDER',
            'MANAGEMENT',
            'PROFESSIONAL_SESSION',
            'FORCED_MANUAL_BLOCK',
            'GDPR_ERASURE'
        )
    ),
    CONSTRAINT chk_res_version CHECK (version >= 1),
    CONSTRAINT chk_res_client_scope_holder CHECK (
        NOT (origin_scope = 'AMB_01_CLIENT' AND holder_id <> requester_id)
    ),
    CONSTRAINT chk_res_updated CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_reservation_holder
    ON reservations.reservation (holder_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reservation_status_expires
    ON reservations.reservation (status, expires_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_reservation_series
    ON reservations.reservation (series_id)
    WHERE series_id IS NOT NULL;

COMMENT ON TABLE reservations.reservation IS
    'EL-RES-02: Cabecera transaccional ACID de reservas (20 columnas, bloqueo optimista version, 0 FKs inter-esquema).';

-- 2. Tabla `reservations.reservation_line` (EL-RES-03, 12 columnas)
CREATE TABLE IF NOT EXISTS reservations.reservation_line (
    id                UUID        NOT NULL,
    reservation_id    UUID        NOT NULL,
    line_number       SMALLINT    NOT NULL,
    resource_id       UUID        NOT NULL,
    resource_root_id  UUID        NOT NULL,
    start_at          TIMESTAMPTZ NOT NULL,
    end_at            TIMESTAMPTZ NOT NULL,
    requested_units   INTEGER     NOT NULL,
    is_exclusive      BOOLEAN     NOT NULL DEFAULT TRUE,
    status            VARCHAR(15) NOT NULL,
    line_amount_minor BIGINT      NOT NULL DEFAULT 0,
    currency_code     CHAR(3)     NOT NULL DEFAULT 'EUR',
    CONSTRAINT pk_reservation_line PRIMARY KEY (id),
    CONSTRAINT uq_res_line_number UNIQUE (reservation_id, line_number),
    CONSTRAINT chk_line_number CHECK (line_number >= 1),
    CONSTRAINT chk_line_interval CHECK (start_at < end_at),
    CONSTRAINT chk_line_units CHECK (requested_units >= 1),
    CONSTRAINT chk_line_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'RELEASED')),
    CONSTRAINT chk_line_amount CHECK (line_amount_minor >= 0),
    CONSTRAINT chk_line_currency CHECK (currency_code ~ '^[A-Z]{3}$')
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint
        WHERE conname = 'fk_res_line_reservation'
    ) THEN
        PERFORM pg_catalog.set_config('search_path', 'reservations, public', true);
        ALTER TABLE reservations.reservation_line
            ADD CONSTRAINT fk_res_line_reservation
            FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_res_line_reservation
    ON reservations.reservation_line (reservation_id);

CREATE INDEX IF NOT EXISTS idx_res_line_active_gist
    ON reservations.reservation_line USING GIST (
        resource_id,
        tstzrange(start_at, end_at, '[)')
    )
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_res_line_root_active
    ON reservations.reservation_line (resource_root_id, start_at)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE reservations.reservation_line IS
    'EL-RES-03: Líneas de reserva por recurso con rango semiabierto [start_at, end_at) e índice GiST activo (ADR-008).';
