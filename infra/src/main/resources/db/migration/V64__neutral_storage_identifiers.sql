-- Neutral storage identifiers for the siege state and person-card projection.
-- Historical V61 and V63 migrations remain immutable.
ALTER TABLE hwiha_siege RENAME TO siege;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_pkey TO siege_pkey;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_world_id_fkey TO siege_world_id_fkey;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_county_fkey TO siege_county_fkey;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_besieger_fkey TO siege_besieger_fkey;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_status_ck TO siege_status_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_end_ck TO siege_end_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_phase_ck TO siege_phase_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_settled_ck TO siege_settled_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_turns_ck TO siege_turns_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_morale_ck TO siege_morale_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_garrison_ck TO siege_garrison_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_nations_ck TO siege_nations_ck;
ALTER TABLE siege RENAME CONSTRAINT hwiha_siege_timeline_ck TO siege_timeline_ck;
ALTER INDEX hwiha_siege_besieger_idx RENAME TO siege_besieger_idx;
ALTER INDEX hwiha_siege_status_idx RENAME TO siege_status_idx;

ALTER VIEW hwiha_person_card RENAME TO person_card;
CREATE OR REPLACE VIEW person_card AS
SELECT g.world_id,
       'general:' || g.id::text AS card_id,
       g.id AS general_id,
       r.id AS retainer_id,
       r.master_general_id AS holder_general_id,
       g.name,
       'UNIQUE'::text AS availability,
       CASE WHEN jsonb_typeof(g.meta -> 'rtk14_birth_year') = 'number' THEN g.born_year ELSE NULL END AS born_year,
       CASE WHEN jsonb_typeof(g.meta -> 'rtk14_death_year') = 'number' THEN g.dead_year ELSE NULL END AS dead_year,
       NULLIF(g.personal_code, 'None') AS personality,
       NULLIF(g.special_code, 'None') AS special_domestic,
       NULLIF(g.special2_code, 'None') AS special_war,
       NULLIF(g.picture, 'default.jpg') AS portrait,
       g.leadership, g.strength, g.intel AS intelligence, g.politics, g.charm,
       CASE WHEN jsonb_typeof(g.meta->'personPolicy') = 'object'
                  AND NULLIF(g.meta #>> '{personPolicy,statSourceId}', '') IS NOT NULL
                  AND NULLIF(g.meta #>> '{personPolicy,statSourceRevision}', '') IS NOT NULL
                  AND g.leadership >= 0 AND g.strength >= 0 AND g.intel >= 0
                  AND g.politics >= 0 AND g.charm >= 0
            THEN greatest(1, ((g.leadership::bigint + g.strength + g.intel + g.politics + g.charm + 49) / 50)::integer)
            ELSE NULL END AS renown_cost,
       g.meta #>> '{personPolicy,statSourceId}' AS stat_source_id,
       g.meta #>> '{personPolicy,statSourceRevision}' AS stat_source_revision,
       g.meta -> 'personBonds' AS bond_state,
       g.meta -> 'personContribution' AS contribution_state,
       r.loyalty,
       g.experience,
       g.injury
FROM general g
LEFT JOIN general_retainers r ON r.world_id = g.world_id AND r.general_id = g.id
UNION ALL
SELECT r.world_id,
       'recruited:' || r.id::text AS card_id,
       NULL::integer AS general_id,
       r.id AS retainer_id,
       r.master_general_id AS holder_general_id,
       r.name,
       'COMMON'::text AS availability,
       NULL::integer, NULL::integer, NULL::text, NULL::text, NULL::text, NULL::text,
       NULL::integer, NULL::integer, NULL::integer, NULL::integer, NULL::integer,
       NULL::integer, NULL::text, NULL::text, NULL::jsonb, NULL::jsonb,
       r.loyalty,
       NULL::integer, NULL::integer
FROM general_retainers r
WHERE r.general_id IS NULL;
