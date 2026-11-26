-- =============================================================================
-- ResourceHub — Migración Inicial Flyway V1
-- Archivo: db/migration/V1__init_extensions_and_schemas.sql
-- Propósito:
--   1. Activar las extensiones aprobadas en PostgreSQL 16+ (`btree_gist`, `ltree`).
--   2. Crear los 11 esquemas físicos aprobados en `RH-MOD-FIS-DAT-v1.1` (§2.2)
--      bajo propiedad exclusiva del rol administrativo `admin`.
--   3. Definir la función base inmutable `public.prevent_append_only_mutation()`
--      para proteger tablas append-only (`RN-TRA-004`, `MDD-013`, `PRU-SEG-08`).
--   4. Endurecer el esquema `public` y aplicar los permisos presentes y futuros
--      (`ALTER DEFAULT PRIVILEGES`) para el rol de aplicación `dba_api`.
-- Alcance (Opción A aprobada — DTE-PG-02):
--   No anticipa la creación de las 34 tablas de dominio, que se crearán
--   incrementalmente en las tareas técnicas `TT-003` a `TT-106`.
-- =============================================================================

-- 1. Extensiones aprobadas (ADR-008, ADR-010, MDD-010, MDD-011)
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;

-- 2. Creación de los 11 esquemas aprobados (RH-MOD-FIS-DAT-v1.1 §2.2, MDD-001)
-- Plano de Plataforma (MOD-01 global)
CREATE SCHEMA IF NOT EXISTS platform_catalog AUTHORIZATION admin;

-- Plano de Organización (MOD-01 local y MOD-02 a MOD-10)
CREATE SCHEMA IF NOT EXISTS org_config       AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS access           AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS resources        AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS availability     AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS reservations     AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS policies         AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS traceability     AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS events           AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS integrations     AUTHORIZATION admin;
CREATE SCHEMA IF NOT EXISTS payments         AUTHORIZATION admin;

-- Comentarios descriptivos de trazabilidad documental en el catálogo PG
COMMENT ON SCHEMA platform_catalog IS 'MOD-01 plataforma (Plano de Plataforma): catálogo de organizaciones, aprovisionamiento, administradores, auditoría de plataforma y error_catalog (DTD-011).';
COMMENT ON SCHEMA org_config       IS 'MOD-01 plataforma (Local al Tenant): parámetros operativos vigentes organization_settings (RF-ORG-004).';
COMMENT ON SCHEMA access           IS 'MOD-02 acceso (PII Vault ADR-015): cuentas de identidad, datos personales, identidades federadas SSO y clientes M2M.';
COMMENT ON SCHEMA resources        IS 'MOD-03 recursos: tipos de recurso (5 naturalezas D-10) y catálogo jerárquico ltree (RN-CAT-004).';
COMMENT ON SCHEMA availability     IS 'MOD-04 disponibilidad: horarios recurrentes semanales IANA (MDD-008), excepciones y bloqueos temporales.';
COMMENT ON SCHEMA reservations     IS 'MOD-05 reservas: núcleo transaccional ACID de series, cabeceras, líneas con exclusión GiST (ADR-008), aprobaciones y lista de espera.';
COMMENT ON SCHEMA policies         IS 'MOD-06 políticas: motor unificado de los 6 tipos canónicos de políticas (D-20) y registro de penalizaciones.';
COMMENT ON SCHEMA traceability     IS 'MOD-07 trazabilidad: histórico de reservas y pista de auditoría autónoma REQUIRES_NEW (ADR-012), estrictamente append-only sin PII.';
COMMENT ON SCHEMA events           IS 'MOD-08 eventos: control transaccional de idempotencia, Transactional Outbox y despacho de notificaciones.';
COMMENT ON SCHEMA integrations     IS 'MOD-09 integraciones: conexiones de calendario soberanas (RN-INT-001) y suscripciones/entregas de webhooks con cuarentena (ADR-017).';
COMMENT ON SCHEMA payments         IS 'MOD-10 pagos: tarifas en unidad entera menor BIGINT (INC-011), transacciones de pasarela y libro contable append-only.';

-- 3. Función reusable para protección física de tablas append-only (RN-TRA-004, PRU-SEG-08)
CREATE OR REPLACE FUNCTION public.prevent_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Operación % prohibida sobre la tabla inmutable (append-only) %.% conforme a RN-TRA-004.',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

ALTER FUNCTION public.prevent_append_only_mutation() OWNER TO admin;
COMMENT ON FUNCTION public.prevent_append_only_mutation() IS 'Función de trigger que aborta cualquier UPDATE, DELETE o TRUNCATE sobre tablas inmutables append-only (RN-TRA-004, MDD-013, PRU-SEG-08).';

-- 4. Endurecimiento del esquema public y permisos para dba_api (Fases 4 y 7)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO admin, dba_api;

DO $$
DECLARE
    s_name text;
    app_schemas constant text[] := ARRAY[
        'platform_catalog',
        'org_config',
        'access',
        'resources',
        'availability',
        'reservations',
        'policies',
        'traceability',
        'events',
        'integrations',
        'payments'
    ];
BEGIN
    -- Verificar que el rol de aplicación dba_api existe antes de aplicar permisos
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'dba_api') THEN
        RAISE EXCEPTION 'El rol obligatorio dba_api no existe en la instancia. Ejecute primero scripts/init-neon-db.sh.';
    END IF;

    -- Permisos a nivel de base de datos para dba_api (CONNECT y TEMPORARY, jamás CREATE)
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC, dba_api', current_database());
    EXECUTE format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO dba_api', current_database());

    -- Privilegios por defecto globales para cualquier objeto o esquema futuro creado por admin
    ALTER DEFAULT PRIVILEGES FOR ROLE admin GRANT USAGE ON SCHEMAS TO dba_api;
    ALTER DEFAULT PRIVILEGES FOR ROLE admin GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dba_api;
    ALTER DEFAULT PRIVILEGES FOR ROLE admin GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO dba_api;
    ALTER DEFAULT PRIVILEGES FOR ROLE admin GRANT EXECUTE ON FUNCTIONS TO dba_api;

    -- Permisos explícitos sobre objetos actuales y futuros en cada uno de los 11 esquemas aprobados
    FOREACH s_name IN ARRAY app_schemas LOOP
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', s_name);
        EXECUTE format('GRANT ALL ON SCHEMA %I TO admin', s_name);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO dba_api', s_name);

        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO dba_api', s_name);
        EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO dba_api', s_name);
        EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO dba_api', s_name);

        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dba_api', s_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA %I GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO dba_api', s_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE admin IN SCHEMA %I GRANT EXECUTE ON FUNCTIONS TO dba_api', s_name);
    END LOOP;

    -- Proteger la tabla de historial de Flyway contra escrituras desde el rol de aplicación dba_api
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history'
    ) THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.flyway_schema_history FROM dba_api;
        GRANT SELECT ON TABLE public.flyway_schema_history TO dba_api;
    END IF;
END;
$$;
