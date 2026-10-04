-- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
-- Preserve the ledger schema and all values; only relation/constraint identifiers change.
ALTER TABLE v2_city_ledger RENAME TO city_ledger;
ALTER TABLE city_ledger RENAME CONSTRAINT v2_city_ledger_pkey TO city_ledger_pkey;
ALTER TABLE city_ledger RENAME CONSTRAINT v2_city_ledger_world_id_fkey TO city_ledger_world_id_fkey;
