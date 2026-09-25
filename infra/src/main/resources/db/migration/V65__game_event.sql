-- ADR-LITE-069: canonical structured events for newly created worlds only.
-- No legacy log_entry conversion or data copy. The engine's normal flush will become the sole writer.
CREATE TABLE game_event (
    world_id                   INTEGER      NOT NULL REFERENCES world_state(id),
    id                         BIGINT       GENERATED ALWAYS AS IDENTITY,
    event_key                  CHAR(64)     NOT NULL,
    kind                       VARCHAR(64)  NOT NULL,
    section                    VARCHAR(24)  NOT NULL,
    audience                   VARCHAR(16)  NOT NULL,
    audience_general_id        INTEGER      NULL,
    audience_nation_id         INTEGER      NULL,
    recipient_general_ids      INTEGER[]    NULL,
    occurred_year              SMALLINT     NOT NULL,
    occurred_month             SMALLINT     NOT NULL,
    occurred_phase             SMALLINT     NOT NULL,
    occurred_ordinal           INTEGER      NOT NULL,
    refs                       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    facts                      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    publication_state          VARCHAR(16)  NOT NULL,
    publish_after_year         SMALLINT     NULL,
    publish_after_month        SMALLINT     NULL,
    publish_after_phase        SMALLINT     NULL,
    recorded_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT game_event_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT game_event_key_uq UNIQUE (world_id, event_key),
    CONSTRAINT game_event_order_uq UNIQUE (world_id, occurred_year, occurred_month, occurred_phase, occurred_ordinal),
    CONSTRAINT game_event_key_ck CHECK (event_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT game_event_section_ck CHECK (section IN ('PERSONAL', 'RETINUE_NATION', 'COURT', 'BATTLE', 'WORLD')),
    CONSTRAINT game_event_audience_ck CHECK (audience IN ('SELF', 'RETINUE', 'NATION', 'COURT', 'PUBLIC')),
    CONSTRAINT game_event_time_ck CHECK (occurred_year BETWEEN 1 AND 9999 AND occurred_month BETWEEN 1 AND 12
        AND occurred_phase BETWEEN 1 AND 3 AND occurred_ordinal >= 0),
    CONSTRAINT game_event_payload_ck CHECK (jsonb_typeof(refs) = 'object' AND jsonb_typeof(facts) = 'object'),
    CONSTRAINT game_event_publication_ck CHECK (
        publication_state IN ('PRIVATE', 'PUBLISHED')
        AND (audience = 'PUBLIC') = (publication_state = 'PUBLISHED')
        AND publish_after_year IS NULL AND publish_after_month IS NULL AND publish_after_phase IS NULL
    ),
    CONSTRAINT game_event_target_ck CHECK (
        (audience = 'SELF' AND audience_general_id IS NOT NULL AND audience_general_id > 0
            AND audience_nation_id IS NULL AND recipient_general_ids IS NULL)
        OR (audience = 'RETINUE' AND audience_general_id IS NOT NULL AND audience_general_id > 0
            AND audience_nation_id IS NULL AND recipient_general_ids IS NOT NULL
            AND cardinality(recipient_general_ids) > 0 AND 0 < ALL(recipient_general_ids)
            AND array_position(recipient_general_ids, NULL) IS NULL)
        OR (audience = 'NATION' AND audience_general_id IS NULL AND audience_nation_id IS NOT NULL
            AND audience_nation_id > 0 AND recipient_general_ids IS NULL)
        OR (audience = 'COURT' AND audience_general_id IS NULL AND audience_nation_id IS NOT NULL
            AND audience_nation_id > 0 AND recipient_general_ids IS NOT NULL
            AND cardinality(recipient_general_ids) > 0 AND 0 < ALL(recipient_general_ids)
            AND array_position(recipient_general_ids, NULL) IS NULL)
        OR (audience = 'PUBLIC' AND audience_general_id IS NULL AND audience_nation_id IS NULL AND recipient_general_ids IS NULL)
    ),
    CONSTRAINT game_event_public_ck CHECK (
        (audience = 'PUBLIC' AND section = 'WORLD' AND facts = '{}'::jsonb AND (
            (kind = 'county.ownerChanged' AND refs ?& ARRAY['CITY', 'FROM_NATION', 'TO_NATION']
                AND refs - 'CITY' - 'FROM_NATION' - 'TO_NATION' = '{}'::jsonb)
            OR (kind = 'roadFort.captured' AND refs ?& ARRAY['ROAD_FORT', 'TO_NATION']
                AND refs - 'ROAD_FORT' - 'FROM_NATION' - 'TO_NATION' = '{}'::jsonb)
            OR (kind = 'yuedan.announced' AND refs = '{}'::jsonb)
        ))
        OR (audience <> 'PUBLIC' AND section <> 'WORLD')
    )
);

-- Read paths use world first, then a recipient or publication boundary and a deterministic cursor.
CREATE INDEX game_event_self_feed_idx ON game_event
    (world_id, audience_general_id, occurred_year DESC, occurred_month DESC, occurred_phase DESC, occurred_ordinal DESC, id DESC)
    WHERE audience = 'SELF';
CREATE INDEX game_event_nation_feed_idx ON game_event
    (world_id, audience_nation_id, occurred_year DESC, occurred_month DESC, occurred_phase DESC, occurred_ordinal DESC, id DESC)
    WHERE audience = 'NATION';
CREATE INDEX game_event_recipient_idx ON game_event USING GIN (recipient_general_ids)
    WHERE audience IN ('RETINUE', 'COURT');
CREATE INDEX game_event_public_feed_idx ON game_event
    (world_id, occurred_year DESC, occurred_month DESC, occurred_phase DESC, occurred_ordinal DESC, id DESC)
    WHERE publication_state = 'PUBLISHED';
