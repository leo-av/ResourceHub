-- =============================================================================
-- ResourceHub — Migración Flyway V8 (Plano de Organización: availability)
-- Archivo: db/migration/organization/V8__init_availability_exceptions.sql
-- Propósito (`TT-015` / `SPEC-DIS-002`):
--   1. Crear `availability.availability_exception` (`EL-DIS-02`, 9 columnas).
--   2. Incorporar restricciones de integridad física y rangos temporales `[start_at, end_at)`.
--   3. Mantener 0 FKs inter-esquema (`ADR-007`).
-- =============================================================================

CREATE TABLE IF NOT EXISTS availability.availability_exception (
    id                 UUID         NOT NULL,
    resource_id        UUID         NOT NULL,
    exception_date     DATE         NOT NULL,
    exception_type     VARCHAR(20)  NOT NULL,
    start_at           TIMESTAMPTZ  NOT NULL,
    end_at             TIMESTAMPTZ  NOT NULL,
    reason             VARCHAR(255) NULL,
    created_by_user_id UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_availability_exception PRIMARY KEY (id),
    CONSTRAINT chk_exc_type CHECK (exception_type IN ('CLOSED', 'CUSTOM_HOURS')),
    CONSTRAINT chk_exc_interval CHECK (start_at < end_at)
);

CREATE INDEX IF NOT EXISTS idx_exc_resource_range
    ON availability.availability_exception (resource_id, start_at, end_at);

COMMENT ON TABLE availability.availability_exception IS
    'EL-DIS-02: Excepciones de calendario Nivel 3 de D-5 (CLOSED y CUSTOM_HOURS) con 0 FKs inter-esquema.';
