-- A free general's existing troop uses nation=0, like general.nation_id.
-- Zero is an absence of allegiance: ScenarioImporter deliberately creates no neutral nation row.
-- Keep the stored value and retain same-world positive-nation and leader foreign keys.
ALTER TABLE troop
    ADD COLUMN nation_ref integer GENERATED ALWAYS AS (NULLIF(nation, 0)) STORED,
    ADD CONSTRAINT troop_nation_nonnegative_check CHECK (nation >= 0);

ALTER TABLE troop DROP CONSTRAINT troop_world_nation_fkey;
ALTER TABLE troop
    ADD CONSTRAINT troop_world_nation_fkey
    FOREIGN KEY (world_id, nation_ref) REFERENCES nation(world_id, id)
    DEFERRABLE INITIALLY DEFERRED;
