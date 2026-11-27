-- =============================================================================
-- ResourceHub — Migración Flyway V4 (Plano de Organización: resources)
-- Archivo: db/migration/organization/V4__init_resources_catalog.sql
-- Propósito (`TT-007` / `SPEC-CAT-001` / `SPEC-CAT-002`):
--   1. Crear `resources.resource_type` (`EL-CAT-01`, 8 columnas, 5 naturalezas D-10).
--   2. Crear `resources.resource` (`EL-CAT-02`, 16 columnas, ciclo CIC-02 y ltree).
--   3. Mantener 0 FKs inter-esquema (`ADR-007`) usando search_path intra-esquema.
-- =============================================================================

SET LOCAL search_path TO resources, public;

-- 1. Tabla `resources.resource_type` (EL-CAT-01, 8 columnas)
CREATE TABLE IF NOT EXISTS resources.resource_type (
    id          UUID         NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    nature_code VARCHAR(20)  NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_resource_type PRIMARY KEY (id),
    CONSTRAINT uq_resource_type_code UNIQUE (code),
    CONSTRAINT chk_res_type_nature CHECK (
        nature_code IN ('SPACE', 'EQUIPMENT', 'PERSON', 'GENERIC_OBJECT', 'CUSTOM')
    ),
    CONSTRAINT chk_res_type_code_not_blank CHECK (char_length(trim(code)) > 0),
    CONSTRAINT chk_res_type_name_not_blank CHECK (char_length(trim(name)) > 0),
    CONSTRAINT chk_res_type_updated CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_resource_type_nature
    ON resources.resource_type (nature_code);

CREATE INDEX IF NOT EXISTS idx_resource_type_active
    ON resources.resource_type (is_active);

COMMENT ON TABLE resources.resource_type IS
    'EL-CAT-01: Catalogo configurable de tipos de recurso bajo las 5 naturalezas canonicas (D-10).';

-- 2. Tabla `resources.resource` (EL-CAT-02, 16 columnas)
CREATE TABLE IF NOT EXISTS resources.resource (
    id                          UUID          NOT NULL,
    resource_type_id            UUID          NOT NULL,
    code                        VARCHAR(50)   NOT NULL,
    name                        VARCHAR(150)  NOT NULL,
    description                 VARCHAR(1000) NULL,
    status                      VARCHAR(15)   NOT NULL DEFAULT 'AVAILABLE',
    capacity                    INTEGER       NOT NULL DEFAULT 1,
    requires_approval           BOOLEAN       NOT NULL DEFAULT FALSE,
    allow_professional_approval BOOLEAN       NOT NULL DEFAULT FALSE,
    parent_id                   UUID          NULL,
    root_id                     UUID          NOT NULL,
    hierarchy_path              LTREE         NOT NULL,
    depth                       SMALLINT      NOT NULL DEFAULT 1,
    linked_user_id              UUID          NULL,
    created_at                  TIMESTAMPTZ   NOT NULL,
    updated_at                  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_resource PRIMARY KEY (id),
    CONSTRAINT uq_resource_code UNIQUE (code),
    CONSTRAINT fk_resource_type FOREIGN KEY (resource_type_id) REFERENCES resource_type (id),
    CONSTRAINT fk_resource_parent FOREIGN KEY (parent_id) REFERENCES resource (id),
    CONSTRAINT fk_resource_root FOREIGN KEY (root_id) REFERENCES resource (id),
    CONSTRAINT chk_resource_status CHECK (status IN ('AVAILABLE', 'DISABLED')),
    CONSTRAINT chk_resource_capacity CHECK (capacity >= 1),
    CONSTRAINT chk_res_no_self_parent CHECK (parent_id IS DISTINCT FROM id),
    CONSTRAINT chk_res_root_consistency CHECK (
        (parent_id IS NULL AND root_id = id AND depth = 1)
        OR (parent_id IS NOT NULL AND depth > 1)
    ),
    CONSTRAINT chk_resource_depth CHECK (depth >= 1),
    CONSTRAINT chk_resource_code_not_blank CHECK (char_length(trim(code)) > 0),
    CONSTRAINT chk_resource_name_not_blank CHECK (char_length(trim(name)) > 0),
    CONSTRAINT chk_resource_updated CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_resource_hierarchy_gist
    ON resources.resource USING GIST (hierarchy_path);

CREATE INDEX IF NOT EXISTS idx_resource_root_id
    ON resources.resource (root_id);

CREATE INDEX IF NOT EXISTS idx_resource_type_id
    ON resources.resource (resource_type_id);

CREATE INDEX IF NOT EXISTS idx_resource_status
    ON resources.resource (status);

CREATE INDEX IF NOT EXISTS idx_resource_linked_user
    ON resources.resource (linked_user_id)
    WHERE linked_user_id IS NOT NULL;

COMMENT ON TABLE resources.resource IS
    'EL-CAT-02: Catalogo de recursos reservables con ciclo CIC-02 (AVAILABLE/DISABLED), capacidad, jerarquia ltree y REF-ID sin FK inter-esquema.';
