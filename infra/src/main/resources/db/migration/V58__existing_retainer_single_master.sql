-- An existing world character can pledge to at most one master.
CREATE UNIQUE INDEX general_retainers_existing_general_unique
    ON general_retainers (world_id, general_id)
    WHERE general_id IS NOT NULL;
