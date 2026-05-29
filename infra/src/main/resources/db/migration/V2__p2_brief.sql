-- P2 (Task FD0a): add the `brief` column PHP writes on every turn row.
--
-- The V1 baseline `general_turn`/`nation_turn` tables have NO `brief` column, but PHP writes one on
-- both (verified `legacy/devsam-core/hwe/sammo/Command/Nation/che_거병.php:151` inserts
-- `'brief'=>'휴식'` on all 24 nation_turn rows; research OQ11). V1 is the frozen baseline — do NOT
-- touch it; add the column in this new migration as a plain `text NOT NULL DEFAULT ''` on each table.
ALTER TABLE general_turn ADD COLUMN brief text NOT NULL DEFAULT '';
ALTER TABLE nation_turn ADD COLUMN brief text NOT NULL DEFAULT '';
