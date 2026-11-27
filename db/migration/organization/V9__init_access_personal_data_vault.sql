-- =============================================================================
-- ResourceHub — Migración Flyway V9 (Plano de Organización: access)
-- Archivo: db/migration/organization/V9__init_access_personal_data_vault.sql
-- Propósito (`TT-016` / `SPEC-ACC-005`):
--   1. Crear `access.personal_data` (*PII Vault*, `EL-ACC-02`, 8 columnas).
--   2. Garantizar unicidad case-insensitive por `LOWER(email)` (`uq_personal_data_email`).
--   3. Mantener FK intra-esquema vía `search_path` hacia `access.identity_account(id)`.
-- =============================================================================

SET LOCAL search_path TO access, public;

CREATE TABLE IF NOT EXISTS access.personal_data (
    user_id                  UUID         NOT NULL,
    email                    VARCHAR(254) NOT NULL,
    full_name                VARCHAR(150) NOT NULL,
    phone                    VARCHAR(32)  NULL,
    password_hash            VARCHAR(255) NULL,
    calendar_sync_consent    BOOLEAN      NOT NULL DEFAULT FALSE,
    calendar_sync_consent_at TIMESTAMPTZ  NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_personal_data PRIMARY KEY (user_id),
    CONSTRAINT fk_personal_data_identity FOREIGN KEY (user_id) REFERENCES identity_account (id),
    CONSTRAINT chk_personal_data_email_not_blank CHECK (char_length(trim(email)) > 0),
    CONSTRAINT chk_personal_data_name_not_blank CHECK (char_length(trim(full_name)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_personal_data_email
    ON access.personal_data (LOWER(email));

COMMENT ON TABLE access.personal_data IS
    'EL-ACC-02: PII Vault aislado (ADR-015) borrable fisicamente en bajas RGPD sin afectar auditorias.';
