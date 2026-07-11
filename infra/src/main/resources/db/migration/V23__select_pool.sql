CREATE TABLE select_pool (
    id             serial PRIMARY KEY,
    unique_name    varchar(20) NOT NULL,
    owner          integer,
    general_id     integer,
    reserved_until timestamptz,
    info           text NOT NULL,
    CONSTRAINT select_pool_unique_name UNIQUE (unique_name),
    CONSTRAINT select_pool_general_id_unique UNIQUE (general_id)
);

CREATE INDEX select_pool_owner_idx ON select_pool (owner);
CREATE INDEX select_pool_reserved_until_general_idx ON select_pool (reserved_until, general_id);
