-- A named battlefield is a sublocation of the authoritative physical node.
ALTER TABLE general_spatial_position
    ADD COLUMN battlefield_id TEXT,
    ADD COLUMN battlefield_catalog_hash TEXT,
    ADD COLUMN battlefield_return_city_id INTEGER,
    ADD CONSTRAINT general_spatial_position_battlefield_presence_check CHECK (
        (battlefield_id IS NULL AND battlefield_catalog_hash IS NULL AND battlefield_return_city_id IS NULL)
        OR
        (battlefield_id IS NOT NULL AND battlefield_catalog_hash IS NOT NULL AND battlefield_return_city_id IS NOT NULL
         AND battlefield_id ~ '^[a-z][a-z0-9-]*$'
         AND battlefield_catalog_hash ~ '^[0-9a-f]{64}$'
         AND battlefield_return_city_id > 0)
    );
