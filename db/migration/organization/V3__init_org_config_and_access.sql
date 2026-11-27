-- =============================================================================
-- ResourceHub — Migración Flyway V3 (Plano de Organización: org_config + access)
-- Archivo: db/migration/organization/V3__init_org_config_and_access.sql
-- Propósito (`TT-005` / `SPEC-ORG-004` / `SPEC-ACC-001`):
--   1. Crear `org_config.organization_settings` (`EL-ORG-05`, singleton MDD-013).
--   2. Sembrar de forma idempotente la configuración inicial de `resourcehub-demo`.
--   3. Crear `access.identity_account` (`EL-ACC-01`, 0 PII según ADR-015).
-- =============================================================================

-- 1. Tabla singleton `org_config.organization_settings` (EL-ORG-05, 13 columnas)
CREATE TABLE IF NOT EXISTS org_config.organization_settings (
    singleton_key                          BOOLEAN     NOT NULL DEFAULT TRUE,
    organization_id                        UUID        NOT NULL,
    time_zone_id                           VARCHAR(64) NOT NULL,
    default_currency_code                  CHAR(3)     NOT NULL DEFAULT 'EUR',
    max_resource_depth                     SMALLINT    NOT NULL DEFAULT 3,
    series_max_horizon_days                INTEGER     NOT NULL DEFAULT 365,
    series_max_occurrences                 INTEGER     NOT NULL DEFAULT 52,
    waitlist_max_entries                   INTEGER     NOT NULL DEFAULT 50,
    waitlist_offer_window_minutes          INTEGER     NOT NULL DEFAULT 15,
    approval_expiration_window_hours       INTEGER     NOT NULL DEFAULT 24,
    attendance_auto_complete_window_hours  INTEGER     NOT NULL DEFAULT 48,
    allow_standard_user_space_booking      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at                             TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_organization_settings PRIMARY KEY (singleton_key),
    CONSTRAINT uq_org_settings_org_id UNIQUE (organization_id),
    CONSTRAINT chk_org_settings_singleton CHECK (singleton_key = TRUE),
    CONSTRAINT chk_org_settings_tz_not_blank CHECK (char_length(trim(time_zone_id)) > 0),
    CONSTRAINT chk_org_currency_iso CHECK (default_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_max_res_depth CHECK (max_resource_depth BETWEEN 1 AND 10),
    CONSTRAINT chk_series_horizon CHECK (series_max_horizon_days BETWEEN 1 AND 1825),
    CONSTRAINT chk_series_max_occ CHECK (series_max_occurrences BETWEEN 1 AND 500),
    CONSTRAINT chk_waitlist_max CHECK (waitlist_max_entries BETWEEN 1 AND 1000),
    CONSTRAINT chk_waitlist_window CHECK (waitlist_offer_window_minutes >= 1),
    CONSTRAINT chk_approval_window CHECK (approval_expiration_window_hours >= 1),
    CONSTRAINT chk_attendance_window CHECK (attendance_auto_complete_window_hours >= 1)
);

COMMENT ON TABLE org_config.organization_settings IS
    'EL-ORG-05: Registro singleton con parámetros operativos vigentes de la organización (MDD-013, 0 PII).';

-- Semilla idempotente para organización inicial `resourcehub-demo`
INSERT INTO org_config.organization_settings (
    singleton_key,
    organization_id,
    time_zone_id,
    default_currency_code,
    max_resource_depth,
    series_max_horizon_days,
    series_max_occurrences,
    waitlist_max_entries,
    waitlist_offer_window_minutes,
    approval_expiration_window_hours,
    attendance_auto_complete_window_hours,
    allow_standard_user_space_booking,
    updated_at
) VALUES (
    TRUE,
    '01936a00-0000-7000-8000-000000000001',
    'Europe/Madrid',
    'EUR',
    3,
    365,
    52,
    50,
    15,
    24,
    48,
    FALSE,
    TIMESTAMPTZ '2026-11-27T00:00:00Z'
) ON CONFLICT (singleton_key) DO NOTHING;

-- 2. Tabla estructural `access.identity_account` (EL-ACC-01, 7 columnas, 0 PII ADR-015)
CREATE TABLE IF NOT EXISTS access.identity_account (
    id                        UUID        NOT NULL,
    role_code                 VARCHAR(10) NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    linked_person_resource_id UUID        NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    deleted_at                TIMESTAMPTZ NULL,
    CONSTRAINT pk_identity_account PRIMARY KEY (id),
    CONSTRAINT chk_identity_role CHECK (role_code IN ('AD', 'MG', 'EM', 'US')),
    CONSTRAINT chk_identity_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED_GDPR')),
    CONSTRAINT chk_identity_person_role CHECK (NOT (role_code = 'US' AND linked_person_resource_id IS NOT NULL)),
    CONSTRAINT chk_identity_updated CHECK (updated_at >= created_at),
    CONSTRAINT chk_identity_deleted_at CHECK ((status = 'DELETED_GDPR') = (deleted_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_identity_linked_person
    ON access.identity_account (linked_person_resource_id)
    WHERE linked_person_resource_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identity_account_role
    ON access.identity_account (role_code);

CREATE INDEX IF NOT EXISTS idx_identity_account_status
    ON access.identity_account (status);

COMMENT ON TABLE access.identity_account IS
    'EL-ACC-01: Identidad opaca sin PII (ADR-015) con rol único (D-2) y vínculo opcional a recurso PERSON (D-11).';
