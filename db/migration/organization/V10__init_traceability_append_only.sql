-- =============================================================================
-- ResourceHub — Migración Flyway V10 (Plano de Organización: traceability)
-- Archivo: db/migration/organization/V10__init_traceability_append_only.sql
-- Propósito (`TT-020` / `SPEC-TRA-001` / `SPEC-TRA-002`):
--   1. Crear `traceability.reservation_history` (`EL-TRA-01`, 13 columnas).
--   2. Crear `traceability.audit_log` (`EL-TRA-02`, 15 columnas).
--   3. Instalar triggers físicos `trg_prevent_append_only_mutation` (RN-TRA-004).
--   4. Mantener 0 PII (`ADR-015`) y 0 FKs inter-esquema (`ADR-007`).
-- =============================================================================

-- 1. Tabla `traceability.reservation_history` (EL-TRA-01, 13 columnas, append-only)
CREATE TABLE IF NOT EXISTS traceability.reservation_history (
    id                   UUID        NOT NULL,
    reservation_id       UUID        NOT NULL,
    change_type          VARCHAR(20) NOT NULL,
    previous_status      VARCHAR(15) NULL,
    new_status           VARCHAR(15) NOT NULL,
    actor_type           VARCHAR(20) NOT NULL,
    requester_id         UUID        NULL,
    holder_id            UUID        NOT NULL,
    operational_scope    VARCHAR(25) NOT NULL,
    snapshot_before_json JSONB       NULL,
    snapshot_after_json  JSONB       NOT NULL,
    correlation_id       VARCHAR(64) NOT NULL,
    occurred_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_reservation_history PRIMARY KEY (id),
    CONSTRAINT chk_hist_change_type CHECK (
        change_type IN (
            'CREATED',
            'MODIFIED',
            'APPROVED',
            'REJECTED',
            'EXPIRED',
            'CANCELLED',
            'COMPLETED',
            'NO_SHOW'
        )
    ),
    CONSTRAINT chk_hist_actor_type CHECK (
        actor_type IN ('USER', 'EXTERNAL_CLIENT', 'SYSTEM')
    )
);

CREATE INDEX IF NOT EXISTS idx_res_hist_reservation_time
    ON traceability.reservation_history (reservation_id, occurred_at ASC);

COMMENT ON TABLE traceability.reservation_history IS
    'EL-TRA-01: Histórico cronológico inmutable append-only de cambios de reserva sin PII (ADR-015, RN-TRA-004).';

-- 2. Tabla `traceability.audit_log` (EL-TRA-02, 15 columnas, append-only REQUIRES_NEW)
CREATE TABLE IF NOT EXISTS traceability.audit_log (
    id                         UUID        NOT NULL,
    correlation_id             VARCHAR(64) NOT NULL,
    operation_code             VARCHAR(60) NOT NULL,
    target_entity_type         VARCHAR(40) NOT NULL,
    target_entity_id           UUID        NULL,
    actor_type                 VARCHAR(20) NOT NULL,
    requester_id               UUID        NULL,
    holder_id                  UUID        NULL,
    actor_role                 VARCHAR(20) NOT NULL,
    operational_scope          VARCHAR(25) NOT NULL,
    outcome                    VARCHAR(15) NOT NULL,
    rejection_category         VARCHAR(40) NULL,
    violated_rule_or_policy_id VARCHAR(64) NULL,
    details_json               JSONB       NOT NULL,
    occurred_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT chk_aud_actor_type CHECK (
        actor_type IN ('USER', 'EXTERNAL_CLIENT', 'SYSTEM')
    ),
    CONSTRAINT chk_aud_outcome CHECK (
        outcome IN ('ACCEPTED', 'REJECTED')
    ),
    CONSTRAINT chk_aud_rej_cat CHECK (
        (outcome = 'ACCEPTED' AND rejection_category IS NULL)
        OR (outcome = 'REJECTED' AND rejection_category IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_audit_occurred_outcome
    ON traceability.audit_log (occurred_at DESC, outcome, operation_code);

COMMENT ON TABLE traceability.audit_log IS
    'EL-TRA-02: Pista de auditoría append-only de operaciones ACCEPTED y REJECTED en tx autónoma (ADR-012, ADR-015).';

-- 3. Protección física append-only contra UPDATE, DELETE y TRUNCATE (RN-TRA-004, PRU-SEG-08)
CREATE OR REPLACE FUNCTION traceability.prevent_append_only_mutation()
RETURNS TRIGGER
AS $$
BEGIN
    RAISE EXCEPTION 'Table %.% is strictly append-only (RN-TRA-004)', TG_TABLE_SCHEMA, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_append_only_mutation ON traceability.reservation_history;
CREATE TRIGGER trg_prevent_append_only_mutation
    BEFORE UPDATE OR DELETE ON traceability.reservation_history
    FOR EACH ROW
    EXECUTE FUNCTION traceability.prevent_append_only_mutation();

DROP TRIGGER IF EXISTS trg_prevent_append_only_truncate ON traceability.reservation_history;
CREATE TRIGGER trg_prevent_append_only_truncate
    BEFORE TRUNCATE ON traceability.reservation_history
    FOR EACH STATEMENT
    EXECUTE FUNCTION traceability.prevent_append_only_mutation();

DROP TRIGGER IF EXISTS trg_prevent_append_only_mutation ON traceability.audit_log;
CREATE TRIGGER trg_prevent_append_only_mutation
    BEFORE UPDATE OR DELETE ON traceability.audit_log
    FOR EACH ROW
    EXECUTE FUNCTION traceability.prevent_append_only_mutation();

DROP TRIGGER IF EXISTS trg_prevent_append_only_truncate ON traceability.audit_log;
CREATE TRIGGER trg_prevent_append_only_truncate
    BEFORE TRUNCATE ON traceability.audit_log
    FOR EACH STATEMENT
    EXECUTE FUNCTION traceability.prevent_append_only_mutation();
