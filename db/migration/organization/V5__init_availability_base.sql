-- =============================================================================
-- ResourceHub — Migración Flyway V5 (Plano de Organización: availability)
-- Archivo: db/migration/organization/V5__init_availability_base.sql
-- Propósito (`TT-009` / `SPEC-DIS-001` / `SPEC-DIS-003`):
--   1. Crear `availability.recurring_schedule` (`EL-DIS-01`, 8 cols, MDD-008).
--   2. Crear `availability.temporal_block` (`EL-DIS-03`, 12 cols, D-5 Nivel 2).
--   3. Mantener 0 FKs inter-esquema (`ADR-007`).
-- =============================================================================

-- 1. Tabla `availability.recurring_schedule` (EL-DIS-01, 8 columnas)
CREATE TABLE IF NOT EXISTS availability.recurring_schedule (
    id               UUID        NOT NULL,
    resource_id      UUID        NOT NULL,
    day_of_week      SMALLINT    NOT NULL,
    start_local_time TIME        NOT NULL,
    end_local_time   TIME        NOT NULL,
    valid_from       DATE        NULL,
    valid_until      DATE        NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recurring_schedule PRIMARY KEY (id),
    CONSTRAINT chk_sched_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_sched_time_order CHECK (start_local_time < end_local_time),
    CONSTRAINT chk_sched_validity CHECK (
        valid_until IS NULL OR valid_from IS NULL OR valid_from <= valid_until
    )
);

CREATE INDEX IF NOT EXISTS idx_recurring_schedule_resource
    ON availability.recurring_schedule (resource_id);

CREATE INDEX IF NOT EXISTS idx_recurring_schedule_dow
    ON availability.recurring_schedule (resource_id, day_of_week);

COMMENT ON TABLE availability.recurring_schedule IS
    'EL-DIS-01: Franjas horarias recurrentes semanales en hora local IANA con desdoblamiento MDD-008 (0 FKs inter-esquema).';

-- 2. Tabla `availability.temporal_block` (EL-DIS-03, 12 columnas)
CREATE TABLE IF NOT EXISTS availability.temporal_block (
    id                         UUID         NOT NULL,
    resource_id                UUID         NOT NULL,
    block_origin               VARCHAR(20)  NOT NULL,
    start_at                   TIMESTAMPTZ  NOT NULL,
    end_at                     TIMESTAMPTZ  NOT NULL,
    forced_confirmation        BOOLEAN      NOT NULL DEFAULT FALSE,
    external_calendar_event_id VARCHAR(255) NULL,
    reason                     VARCHAR(255) NULL,
    is_active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_user_id         UUID         NULL,
    created_at                 TIMESTAMPTZ  NOT NULL,
    released_at                TIMESTAMPTZ  NULL,
    CONSTRAINT pk_temporal_block PRIMARY KEY (id),
    CONSTRAINT chk_block_origin CHECK (
        block_origin IN ('MANUAL', 'MAINTENANCE', 'SYSTEM', 'CALENDAR')
    ),
    CONSTRAINT chk_block_interval CHECK (start_at < end_at),
    CONSTRAINT chk_block_cal_not_forced CHECK (
        NOT (block_origin = 'CALENDAR' AND forced_confirmation = TRUE)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_block_ext_cal_event
    ON availability.temporal_block (resource_id, external_calendar_event_id)
    WHERE external_calendar_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_temporal_block_resource_active
    ON availability.temporal_block (resource_id, is_active);

CREATE INDEX IF NOT EXISTS idx_temporal_block_gist
    ON availability.temporal_block USING GIST (
        resource_id,
        tstzrange(start_at, end_at, '[)')
    )
    WHERE is_active = TRUE;

COMMENT ON TABLE availability.temporal_block IS
    'EL-DIS-03: Bloqueos temporales de indisponibilidad Nivel 2 de D-5 (MANUAL, MAINTENANCE, SYSTEM, CALENDAR).';
