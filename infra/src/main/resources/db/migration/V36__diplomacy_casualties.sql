ALTER TABLE diplomacy
    ADD COLUMN casualties integer NOT NULL DEFAULT 0;

UPDATE diplomacy
   SET casualties = 1
 WHERE is_dead;
