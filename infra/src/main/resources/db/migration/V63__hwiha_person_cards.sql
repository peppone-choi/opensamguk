-- §6.2 person-card projection over the V55 retainer storage and V58 single-master index.
-- V62 is reserved by lane 7 PR #876; merge that PR before this migration reaches main.
-- Keep V55 as the sole write authority while legacy and HWIHA worlds coexist: a copied card
-- table would allow a pledge/release to leave two conflicting ownership records. The view
-- exposes every general as a unique person card, held when V55 has a linked row, plus every
-- V55 recruited anonymous card as common. The V58 unique index still enforces one holder.
-- Missing source-validated stats yield NULL renown cost, never importer-default 50/50.
CREATE VIEW hwiha_person_card AS
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
       CASE WHEN jsonb_typeof(g.meta->'hwihaPersonPolicy') = 'object'
                  AND NULLIF(g.meta #>> '{hwihaPersonPolicy,statSourceId}', '') IS NOT NULL
                  AND NULLIF(g.meta #>> '{hwihaPersonPolicy,statSourceRevision}', '') IS NOT NULL
                  AND g.leadership >= 0 AND g.strength >= 0 AND g.intel >= 0
                  AND g.politics >= 0 AND g.charm >= 0
            THEN greatest(1, ((g.leadership::bigint + g.strength + g.intel + g.politics + g.charm + 49) / 50)::integer)
            ELSE NULL END AS renown_cost,
       g.meta #>> '{hwihaPersonPolicy,statSourceId}' AS stat_source_id,
       g.meta #>> '{hwihaPersonPolicy,statSourceRevision}' AS stat_source_revision,
       g.meta -> 'hwihaPersonBonds' AS bond_state,
       g.meta -> 'hwihaPersonContribution' AS contribution_state,
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
