-- =============================================================================
-- ResourceHub — Migración Flyway V7 (Plano de Organización: reservations)
-- Archivo: db/migration/organization/V7__add_reservations_gist_exclusion.sql
-- Propósito (`TT-014` / `SPEC-RES-002` / `ADR-008`):
--   Añadir la barrera física PostgreSQL 16+ `EXCLUDE USING gist` sobre
--   `reservations.reservation_line` para impedir solapamientos concurrentes en
--   líneas activas exclusivas usando intervalos semiabiertos `[start_at, end_at)`.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint
        WHERE conname = 'excl_res_line_active_overlap'
    ) THEN
        ALTER TABLE reservations.reservation_line
            ADD CONSTRAINT excl_res_line_active_overlap
            EXCLUDE USING gist (
                resource_id WITH =,
                tstzrange(start_at, end_at, '[)') WITH &&
            )
            WHERE (status = 'ACTIVE' AND is_exclusive = TRUE);
    END IF;
END
$$;
