-- P6 (Tier-0 T0.1): messaging + economy schema.
--
-- Faithful port of the PHP grand-truth tables (`legacy/devsam-core/hwe/sql/schema.sql`):
--   `message` (:241), `ng_betting` (:644), `ng_auction` (:690), `ng_auction_bid` (:709),
--   plus the string-namespace KV store (PHP `storage`, :567) that backs `game_env` /
--   `betting` / `inheritance_{id}` (the non-nation-int namespaces the V3 `nation_env` table
--   cannot hold). Postgres-ized; V1-V6 are frozen, so all new shapes land here.
--
-- AUCTION RECONCILIATION (justified): the V1 baseline `auction`/`auction_bid` tables are
-- TS-shaped placeholders (status/latest_event_id/meta) that were never read or written by any
-- RowMapper or the JdbcFlushExecutor through P1-P5 — they appear ONLY as string literals in
-- `TruncateContract.TRUNCATED` (the per-season reset allow-list). They are therefore empty and
-- unused. We DROP+CREATE them into the PHP `ng_auction`/`ng_auction_bid` shape (finished BIT →
-- boolean, req_resource ENUM gold/rice/inheritPoint, open_date/close_date, detail/aux jsonb) and
-- rename to the PHP table names so the P6 economy families port column-for-column. This does NOT
-- edit V1 (its CREATE statements stay frozen; Flyway checksums of V1-V6 are untouched). The
-- `auction_type`/`auction_status` V1 enums are dropped alongside (they encoded the TS shape).

-- ---------------------------------------------------------------------------
-- message (mailbox routing + polymorphic message body)
-- ---------------------------------------------------------------------------
CREATE TYPE message_type AS ENUM ('private', 'national', 'public', 'diplomacy');

CREATE TABLE message (
    id          serial PRIMARY KEY,
    mailbox     integer NOT NULL,                 -- 9999 == public, >= 9000 national
    type        message_type NOT NULL,
    src         integer NOT NULL,
    dest        integer NOT NULL,
    time        timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz NOT NULL DEFAULT '9999-12-31 23:59:59',
    message     jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX message_by_mailbox ON message (mailbox, type, id);

-- ---------------------------------------------------------------------------
-- ng_betting (per-general bet rows; amount += on re-bet via the UPSERT path)
-- PHP: UNIQUE(general_id,betting_id,betting_type) + UNIQUE(betting_id,betting_type,general_id).
-- ---------------------------------------------------------------------------
CREATE TABLE ng_betting (
    id           serial PRIMARY KEY,
    betting_id   integer NOT NULL,
    general_id   integer NOT NULL,
    user_id      integer,
    betting_type varchar(100) NOT NULL,
    amount       integer NOT NULL,
    UNIQUE (general_id, betting_id, betting_type),
    UNIQUE (betting_id, betting_type, general_id)
);
CREATE INDEX ng_betting_by_user ON ng_betting (user_id, betting_id, betting_type);

-- ---------------------------------------------------------------------------
-- game_kv: string-namespace KV store (PHP `storage` shape, generalized with a `table`
-- discriminator so one relation backs game_env / betting / inheritance_{id} string namespaces).
-- The nation-int-namespace store stays in V3 `nation_env`; this is the string-namespace sibling.
-- value jsonb; delete-on-null is enforced by the flush executor (KVStorage.php delete semantics).
-- ---------------------------------------------------------------------------
CREATE TABLE game_kv (
    id        serial PRIMARY KEY,
    "table"   text NOT NULL,
    namespace text NOT NULL,
    key       text NOT NULL,
    value     jsonb NOT NULL,
    UNIQUE ("table", namespace, key)
);
CREATE INDEX game_kv_by_namespace ON game_kv ("table", namespace);

-- ---------------------------------------------------------------------------
-- ng_auction / ng_auction_bid (reconciled from V1 auction/auction_bid to the PHP shape).
-- ---------------------------------------------------------------------------
DROP TABLE auction_bid;
DROP TABLE auction;
DROP TYPE auction_status;
DROP TYPE auction_type;

CREATE TYPE ng_auction_type AS ENUM ('buyRice', 'sellRice', 'uniqueItem');
CREATE TYPE ng_auction_resource AS ENUM ('gold', 'rice', 'inheritPoint');

CREATE TABLE ng_auction (
    id              serial PRIMARY KEY,
    type            ng_auction_type NOT NULL,
    finished        boolean NOT NULL DEFAULT false,
    target          varchar(50),
    host_general_id integer NOT NULL,
    req_resource    ng_auction_resource NOT NULL,
    open_date       timestamptz NOT NULL,
    close_date      timestamptz NOT NULL,
    detail          jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ng_auction_by_close ON ng_auction (finished, type, close_date);
CREATE INDEX ng_auction_by_general ON ng_auction (host_general_id, type, finished);

CREATE TABLE ng_auction_bid (
    no         serial PRIMARY KEY,
    auction_id integer NOT NULL,
    owner      integer,
    general_id integer NOT NULL,
    amount     integer NOT NULL,
    date       timestamptz NOT NULL,
    aux        jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (general_id, auction_id, amount),
    UNIQUE (auction_id, amount)
);
