-- Esquema de la PoC. En produccion: auditoria en un esquema/rol separado con solo INSERT/SELECT
-- (REVOKE UPDATE, DELETE) y exportacion continua a almacenamiento WORM.

CREATE TABLE transferencia (
    id              UUID PRIMARY KEY,
    tipo            VARCHAR(20)    NOT NULL,
    cuenta_origen   VARCHAR(20)    NOT NULL,
    cuenta_destino  VARCHAR(20)    NOT NULL,
    banco_destino   VARCHAR(60),
    monto           NUMERIC(18, 2) NOT NULL CHECK (monto > 0),
    moneda          CHAR(3)        NOT NULL,
    referencia      VARCHAR(140)   NOT NULL DEFAULT '',
    estado          VARCHAR(20)    NOT NULL,
    solicitante     VARCHAR(100)   NOT NULL,
    creada_en       TIMESTAMPTZ    NOT NULL
);

CREATE TABLE idempotencia (
    clave             VARCHAR(64) PRIMARY KEY,
    recurso           VARCHAR(100) NOT NULL,
    huella_solicitud  CHAR(64)     NOT NULL,
    transferencia_id  UUID         NOT NULL,
    creada_en         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Retencion segun ADR-009: 7 dias (job de purga por creada_en); fuera de alcance de la PoC.

CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    tipo          VARCHAR(100) NOT NULL,
    agregado_id   UUID         NOT NULL,
    payload       JSONB        NOT NULL,
    creado_en     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    publicado_en  TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pendientes ON outbox (creado_en) WHERE publicado_en IS NULL;

CREATE TABLE auditoria (
    id             BIGSERIAL PRIMARY KEY,
    ocurrido_en    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(100) NOT NULL,
    accion         VARCHAR(60)  NOT NULL,
    recurso        VARCHAR(200) NOT NULL,
    detalle        TEXT         NOT NULL,
    hash_anterior  CHAR(64)     NOT NULL,
    hash           CHAR(64)     NOT NULL UNIQUE
);
