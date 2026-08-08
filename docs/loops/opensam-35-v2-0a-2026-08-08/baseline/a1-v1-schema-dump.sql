--
--



SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: diplomacy_letter_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.diplomacy_letter_state AS ENUM (
    'PROPOSED',
    'ACTIVATED',
    'CANCELLED',
    'REPLACED'
);


--
-- Name: log_category; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.log_category AS ENUM (
    'HISTORY',
    'SUMMARY',
    'ACTION',
    'BATTLE_BRIEF',
    'BATTLE_DETAIL',
    'USER'
);


--
-- Name: log_scope; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.log_scope AS ENUM (
    'SYSTEM',
    'NATION',
    'GENERAL',
    'USER'
);


--
-- Name: message_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.message_type AS ENUM (
    'private',
    'national',
    'public',
    'diplomacy'
);


--
-- Name: ng_auction_resource; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.ng_auction_resource AS ENUM (
    'gold',
    'rice',
    'inheritPoint'
);


--
-- Name: ng_auction_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.ng_auction_type AS ENUM (
    'buyRice',
    'sellRice',
    'uniqueItem'
);


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: banned_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.banned_member (
    id bigint NOT NULL,
    hashed_email character varying(128) NOT NULL,
    info text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: banned_member_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.banned_member_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: banned_member_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.banned_member_id_seq OWNED BY public.banned_member.id;


--
-- Name: board_comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_comment (
    id integer NOT NULL,
    post_id integer NOT NULL,
    nation_id integer NOT NULL,
    is_secret boolean DEFAULT false NOT NULL,
    author_general_id integer NOT NULL,
    author_name text NOT NULL,
    content_text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: board_comment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.board_comment_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: board_comment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.board_comment_id_seq OWNED BY public.board_comment.id;


--
-- Name: board_post; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_post (
    id integer NOT NULL,
    nation_id integer NOT NULL,
    is_secret boolean DEFAULT false NOT NULL,
    author_general_id integer NOT NULL,
    author_name text NOT NULL,
    title text NOT NULL,
    content_html text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    author_icon text,
    world_id integer NOT NULL
);


--
-- Name: board_post_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.board_post_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: board_post_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.board_post_id_seq OWNED BY public.board_post.id;


--
-- Name: city; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.city (
    id integer NOT NULL,
    name text NOT NULL,
    level integer NOT NULL,
    nation_id integer DEFAULT 0 NOT NULL,
    supply_state integer DEFAULT 1 NOT NULL,
    front_state integer DEFAULT 0 NOT NULL,
    pop integer NOT NULL,
    pop_max integer NOT NULL,
    agri integer NOT NULL,
    agri_max integer NOT NULL,
    comm integer NOT NULL,
    comm_max integer NOT NULL,
    secu integer NOT NULL,
    secu_max integer NOT NULL,
    trust double precision DEFAULT 0 NOT NULL,
    trade integer,
    def integer NOT NULL,
    def_max integer NOT NULL,
    wall integer NOT NULL,
    wall_max integer NOT NULL,
    region integer NOT NULL,
    conflict jsonb DEFAULT '{}'::jsonb NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    term integer DEFAULT 0 NOT NULL,
    officer_set integer DEFAULT 0 NOT NULL,
    state integer DEFAULT 0 NOT NULL,
    dead integer DEFAULT 0 NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: command_inbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.command_inbox (
    world_id integer NOT NULL,
    request_id text NOT NULL,
    payload_schema_version integer NOT NULL,
    command_kind text NOT NULL,
    status text NOT NULL,
    intent_fingerprint text NOT NULL,
    general_id integer,
    turn_idx integer,
    action_code text,
    payload jsonb NOT NULL,
    redis_wake_published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    claimed_at timestamp with time zone,
    claim_expires_at timestamp with time zone,
    CONSTRAINT command_inbox_kind_check CHECK ((command_kind = ANY (ARRAY['IMMEDIATE'::text, 'RESERVED_TURN'::text, 'QUEUE_MUTATION'::text]))),
    CONSTRAINT command_inbox_schema_positive CHECK ((payload_schema_version > 0)),
    CONSTRAINT command_inbox_status_check CHECK ((status = ANY (ARRAY['ACCEPTED'::text, 'CLAIMED'::text, 'APPLIED'::text, 'REJECTED'::text])))
);


--
-- Name: command_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.command_outbox (
    world_id integer NOT NULL,
    event_id text NOT NULL,
    request_id text NOT NULL,
    event_type text NOT NULL,
    payload_schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT command_outbox_schema_positive CHECK ((payload_schema_version > 0))
);


--
-- Name: command_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.command_result (
    world_id integer NOT NULL,
    request_id text NOT NULL,
    result_seq integer NOT NULL,
    terminal_status text NOT NULL,
    result_type text NOT NULL,
    ok boolean NOT NULL,
    committed_world_version bigint NOT NULL,
    payload_schema_version integer NOT NULL,
    result_payload jsonb NOT NULL,
    sent_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT command_result_schema_positive CHECK ((payload_schema_version > 0)),
    CONSTRAINT command_result_seq_positive CHECK ((result_seq > 0)),
    CONSTRAINT command_result_status_check CHECK ((terminal_status = ANY (ARRAY['APPLIED'::text, 'REJECTED'::text]))),
    CONSTRAINT command_result_world_version_non_negative CHECK ((committed_world_version >= 0))
);


--
-- Name: diplomacy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.diplomacy (
    id integer NOT NULL,
    src_nation_id integer NOT NULL,
    dest_nation_id integer NOT NULL,
    state_code integer NOT NULL,
    term integer DEFAULT 0 NOT NULL,
    is_dead boolean DEFAULT false NOT NULL,
    is_showing boolean DEFAULT true NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL,
    casualties integer DEFAULT 0 NOT NULL
);


--
-- Name: diplomacy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.diplomacy_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: diplomacy_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.diplomacy_id_seq OWNED BY public.diplomacy.id;


--
-- Name: diplomacy_letter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.diplomacy_letter (
    id integer NOT NULL,
    src_nation_id integer NOT NULL,
    dest_nation_id integer NOT NULL,
    prev_id integer,
    state public.diplomacy_letter_state DEFAULT 'PROPOSED'::public.diplomacy_letter_state NOT NULL,
    text_brief text NOT NULL,
    text_detail text NOT NULL,
    date timestamp with time zone DEFAULT now() NOT NULL,
    src_signer integer NOT NULL,
    dest_signer integer,
    aux jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: diplomacy_letter_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.diplomacy_letter_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: diplomacy_letter_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.diplomacy_letter_id_seq OWNED BY public.diplomacy_letter.id;


--
-- Name: emperior; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.emperior (
    id integer NOT NULL,
    phase text NOT NULL,
    server_id text NOT NULL,
    nation_count text NOT NULL,
    nation_name text NOT NULL,
    nation_hist text NOT NULL,
    gen_count text NOT NULL,
    personal_hist text NOT NULL,
    special_hist text NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    color text NOT NULL,
    year integer NOT NULL,
    month integer NOT NULL,
    power integer DEFAULT 0 NOT NULL,
    gennum integer DEFAULT 0 NOT NULL,
    citynum integer DEFAULT 0 NOT NULL,
    pop text NOT NULL,
    poprate text NOT NULL,
    gold integer DEFAULT 0 NOT NULL,
    rice integer DEFAULT 0 NOT NULL,
    l12name text,
    l12pic text,
    l11name text,
    l11pic text,
    l10name text,
    l10pic text,
    l9name text,
    l9pic text,
    l8name text,
    l8pic text,
    l7name text,
    l7pic text,
    l6name text,
    l6pic text,
    l5name text,
    l5pic text,
    tiger text DEFAULT ''::text NOT NULL,
    eagle text DEFAULT ''::text NOT NULL,
    gen text DEFAULT ''::text NOT NULL,
    history jsonb DEFAULT '[]'::jsonb NOT NULL,
    aux jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: emperior_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.emperior_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: emperior_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.emperior_id_seq OWNED BY public.emperior.id;


--
-- Name: error_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.error_log (
    id integer NOT NULL,
    category text NOT NULL,
    source text,
    message text NOT NULL,
    trace text,
    context jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: error_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.error_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: error_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.error_log_id_seq OWNED BY public.error_log.id;


--
-- Name: event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.event (
    id integer NOT NULL,
    target_code text NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    condition jsonb DEFAULT '{}'::jsonb NOT NULL,
    action jsonb DEFAULT '{}'::jsonb NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.event_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.event_id_seq OWNED BY public.event.id;


--
-- Name: game_kv; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_kv (
    id integer NOT NULL,
    "table" text NOT NULL,
    namespace text NOT NULL,
    key text NOT NULL,
    value jsonb NOT NULL,
    world_id integer,
    CONSTRAINT game_kv_world_ownership_check CHECK (((("table" = 'inheritance'::text) AND (world_id IS NULL)) OR (("table" <> 'inheritance'::text) AND (world_id IS NOT NULL))))
);


--
-- Name: game_kv_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.game_kv_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: game_kv_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.game_kv_id_seq OWNED BY public.game_kv.id;


--
-- Name: general; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.general (
    id integer NOT NULL,
    user_id text,
    name text NOT NULL,
    nation_id integer DEFAULT 0 NOT NULL,
    city_id integer DEFAULT 0 NOT NULL,
    troop_id integer DEFAULT 0 NOT NULL,
    npc_state integer DEFAULT 0 NOT NULL,
    affinity integer,
    born_year integer DEFAULT 180 NOT NULL,
    dead_year integer DEFAULT 300 NOT NULL,
    picture text,
    image_server integer DEFAULT 0 NOT NULL,
    leadership integer DEFAULT 50 NOT NULL,
    strength integer DEFAULT 50 NOT NULL,
    intel integer DEFAULT 50 NOT NULL,
    injury integer DEFAULT 0 NOT NULL,
    experience integer DEFAULT 0 NOT NULL,
    dedication integer DEFAULT 0 NOT NULL,
    officer_level integer DEFAULT 0 NOT NULL,
    gold integer DEFAULT 1000 NOT NULL,
    rice integer DEFAULT 1000 NOT NULL,
    crew integer DEFAULT 0 NOT NULL,
    crew_type_id integer DEFAULT 0 NOT NULL,
    train integer DEFAULT 0 NOT NULL,
    atmos integer DEFAULT 0 NOT NULL,
    weapon_code text DEFAULT 'None'::text NOT NULL,
    book_code text DEFAULT 'None'::text NOT NULL,
    horse_code text DEFAULT 'None'::text NOT NULL,
    item_code text DEFAULT 'None'::text NOT NULL,
    turn_time timestamp with time zone NOT NULL,
    recent_war_time timestamp with time zone,
    age integer DEFAULT 20 NOT NULL,
    start_age integer DEFAULT 20 NOT NULL,
    personal_code text DEFAULT 'None'::text NOT NULL,
    special_code text DEFAULT 'None'::text NOT NULL,
    special2_code text DEFAULT 'None'::text NOT NULL,
    last_turn jsonb DEFAULT '{}'::jsonb NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    penalty jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    officer_city integer DEFAULT 0 NOT NULL,
    politics integer DEFAULT 50 NOT NULL,
    charm integer DEFAULT 50 NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: general_access_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.general_access_log (
    id bigint NOT NULL,
    general_id integer NOT NULL,
    user_id bigint,
    last_refresh timestamp with time zone,
    refresh integer DEFAULT 0 NOT NULL,
    refresh_total integer DEFAULT 0 NOT NULL,
    refresh_score integer DEFAULT 0 NOT NULL,
    refresh_score_total integer DEFAULT 0 NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: general_access_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.general_access_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: general_access_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.general_access_log_id_seq OWNED BY public.general_access_log.id;


--
-- Name: general_owner; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.general_owner (
    general_id bigint NOT NULL,
    user_id bigint NOT NULL,
    claimed_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL,
    claim_request_id text
);


--
-- Name: general_turn; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.general_turn (
    id integer NOT NULL,
    general_id integer NOT NULL,
    turn_idx integer NOT NULL,
    action_code text NOT NULL,
    arg jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    brief text DEFAULT ''::text NOT NULL,
    world_id integer NOT NULL,
    request_id text
);


--
-- Name: general_turn_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.general_turn_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: general_turn_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.general_turn_id_seq OWNED BY public.general_turn.id;


--
-- Name: hall; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hall (
    id integer NOT NULL,
    server_id text NOT NULL,
    season integer NOT NULL,
    scenario integer NOT NULL,
    general_no integer NOT NULL,
    type character varying(20) NOT NULL,
    value double precision NOT NULL,
    owner text,
    aux jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: hall_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.hall_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: hall_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.hall_id_seq OWNED BY public.hall.id;


--
-- Name: inheritance_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inheritance_log (
    id integer NOT NULL,
    user_id text NOT NULL,
    year integer NOT NULL,
    month integer NOT NULL,
    text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    date timestamp with time zone
);


--
-- Name: inheritance_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.inheritance_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: inheritance_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.inheritance_log_id_seq OWNED BY public.inheritance_log.id;


--
-- Name: inheritance_point; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inheritance_point (
    id integer NOT NULL,
    user_id text NOT NULL,
    key text NOT NULL,
    value double precision DEFAULT 0 NOT NULL,
    aux jsonb DEFAULT '{}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: inheritance_point_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.inheritance_point_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: inheritance_point_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.inheritance_point_id_seq OWNED BY public.inheritance_point.id;


--
-- Name: inheritance_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inheritance_result (
    id integer NOT NULL,
    server_id text NOT NULL,
    owner text NOT NULL,
    general_id integer NOT NULL,
    year integer NOT NULL,
    month integer NOT NULL,
    value jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: inheritance_result_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.inheritance_result_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: inheritance_result_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.inheritance_result_id_seq OWNED BY public.inheritance_result.id;


--
-- Name: inheritance_user_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inheritance_user_state (
    user_id text NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: log_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.log_entry (
    id integer NOT NULL,
    scope public.log_scope NOT NULL,
    category public.log_category NOT NULL,
    sub_type text,
    year integer NOT NULL,
    month integer NOT NULL,
    text text NOT NULL,
    general_id integer,
    nation_id integer,
    user_id integer,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    phase integer DEFAULT 1 NOT NULL,
    world_id integer NOT NULL,
    CONSTRAINT log_entry_phase_chk CHECK (((phase >= 1) AND (phase <= 3)))
);


--
-- Name: log_entry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.log_entry_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: log_entry_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.log_entry_id_seq OWNED BY public.log_entry.id;


--
-- Name: message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message (
    id integer NOT NULL,
    mailbox integer NOT NULL,
    type public.message_type NOT NULL,
    src integer NOT NULL,
    dest integer NOT NULL,
    "time" timestamp with time zone DEFAULT now() NOT NULL,
    valid_until timestamp with time zone DEFAULT '9999-12-31 23:59:59+00'::timestamp with time zone NOT NULL,
    message jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.message_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.message_id_seq OWNED BY public.message.id;


--
-- Name: nation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nation (
    id integer NOT NULL,
    name text NOT NULL,
    color text NOT NULL,
    capital_city_id integer,
    gold integer DEFAULT 0 NOT NULL,
    rice integer DEFAULT 0 NOT NULL,
    tech double precision DEFAULT 0 NOT NULL,
    level integer DEFAULT 0 NOT NULL,
    type_code text DEFAULT 'che_중립'::text NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    power integer DEFAULT 0 NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: nation_env; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nation_env (
    id integer NOT NULL,
    namespace integer NOT NULL,
    key text NOT NULL,
    value jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: nation_env_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nation_env_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nation_env_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nation_env_id_seq OWNED BY public.nation_env.id;


--
-- Name: nation_turn; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nation_turn (
    id integer NOT NULL,
    nation_id integer NOT NULL,
    officer_level integer NOT NULL,
    turn_idx integer NOT NULL,
    action_code text NOT NULL,
    arg jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    brief text DEFAULT ''::text NOT NULL,
    world_id integer NOT NULL,
    request_id text
);


--
-- Name: nation_turn_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.nation_turn_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: nation_turn_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.nation_turn_id_seq OWNED BY public.nation_turn.id;


--
-- Name: ng_auction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_auction (
    id integer NOT NULL,
    type public.ng_auction_type NOT NULL,
    finished boolean DEFAULT false NOT NULL,
    target character varying(50),
    host_general_id integer NOT NULL,
    req_resource public.ng_auction_resource NOT NULL,
    open_date timestamp with time zone NOT NULL,
    close_date timestamp with time zone NOT NULL,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_auction_bid; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_auction_bid (
    no integer NOT NULL,
    auction_id integer NOT NULL,
    owner integer,
    general_id integer NOT NULL,
    amount integer NOT NULL,
    date timestamp with time zone NOT NULL,
    aux jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_auction_bid_no_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_auction_bid_no_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_auction_bid_no_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_auction_bid_no_seq OWNED BY public.ng_auction_bid.no;


--
-- Name: ng_auction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_auction_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_auction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_auction_id_seq OWNED BY public.ng_auction.id;


--
-- Name: ng_betting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_betting (
    id integer NOT NULL,
    betting_id integer NOT NULL,
    general_id integer NOT NULL,
    user_id integer,
    betting_type character varying(100) NOT NULL,
    amount integer NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_betting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_betting_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_betting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_betting_id_seq OWNED BY public.ng_betting.id;


--
-- Name: ng_games; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_games (
    id integer NOT NULL,
    server_id text NOT NULL,
    date timestamp with time zone NOT NULL,
    winner_nation integer,
    map text,
    season integer NOT NULL,
    scenario integer NOT NULL,
    scenario_name text NOT NULL,
    env jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_games_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_games_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_games_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_games_id_seq OWNED BY public.ng_games.id;


--
-- Name: ng_old_generals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_old_generals (
    id integer NOT NULL,
    server_id text NOT NULL,
    general_no integer NOT NULL,
    owner text,
    name text NOT NULL,
    last_yearmonth integer NOT NULL,
    turntime timestamp with time zone NOT NULL,
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_old_generals_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_old_generals_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_old_generals_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_old_generals_id_seq OWNED BY public.ng_old_generals.id;


--
-- Name: ng_old_nations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ng_old_nations (
    id integer NOT NULL,
    server_id text NOT NULL,
    nation integer DEFAULT 0 NOT NULL,
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    date timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: ng_old_nations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ng_old_nations_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ng_old_nations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ng_old_nations_id_seq OWNED BY public.ng_old_nations.id;


--
-- Name: rank_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rank_data (
    id integer NOT NULL,
    nation_id integer DEFAULT 0 NOT NULL,
    general_id integer NOT NULL,
    type character varying(20) NOT NULL,
    value integer DEFAULT 0 NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: rank_data_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.rank_data_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: rank_data_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.rank_data_id_seq OWNED BY public.rank_data.id;


--
-- Name: select_npc_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.select_npc_token (
    id bigint NOT NULL,
    owner_id bigint NOT NULL,
    valid_until timestamp with time zone NOT NULL,
    pick_more_from timestamp with time zone NOT NULL,
    pick_result jsonb DEFAULT '{}'::jsonb NOT NULL,
    nonce integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: select_npc_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.select_npc_token_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: select_npc_token_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.select_npc_token_id_seq OWNED BY public.select_npc_token.id;


--
-- Name: select_pool; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.select_pool (
    id integer NOT NULL,
    unique_name character varying(20) NOT NULL,
    owner integer,
    general_id integer,
    reserved_until timestamp with time zone,
    info text NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: select_pool_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.select_pool_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: select_pool_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.select_pool_id_seq OWNED BY public.select_pool.id;


--
-- Name: statistic; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.statistic (
    id integer NOT NULL,
    year integer DEFAULT 0 NOT NULL,
    month integer DEFAULT 0 NOT NULL,
    nation_count integer DEFAULT 0 NOT NULL,
    nation_name text DEFAULT ''::text NOT NULL,
    nation_hist text DEFAULT ''::text NOT NULL,
    gen_count text DEFAULT ''::text NOT NULL,
    personal_hist text DEFAULT ''::text NOT NULL,
    special_hist text DEFAULT ''::text NOT NULL,
    power_hist text DEFAULT ''::text NOT NULL,
    crewtype text DEFAULT ''::text NOT NULL,
    etc text DEFAULT ''::text NOT NULL,
    aux jsonb,
    world_id integer NOT NULL
);


--
-- Name: statistic_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.statistic_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: statistic_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.statistic_id_seq OWNED BY public.statistic.id;


--
-- Name: system_flag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_flag (
    id bigint NOT NULL,
    allow_join boolean DEFAULT false NOT NULL,
    allow_login boolean DEFAULT false NOT NULL,
    notice character varying(256) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: system_flag_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.system_flag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_flag_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.system_flag_id_seq OWNED BY public.system_flag.id;


--
-- Name: troop; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.troop (
    troop_leader integer NOT NULL,
    nation integer NOT NULL,
    name text NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    password character varying(255) NOT NULL,
    email character varying(100),
    nickname character varying(50),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    grade integer,
    block_until timestamp with time zone,
    delete_after timestamp with time zone,
    oauth_type character varying(16),
    picture character varying(64),
    imgsvr boolean DEFAULT false NOT NULL,
    last_login_at timestamp with time zone,
    profile_icon_changed_at timestamp with time zone,
    profile_icon_managed boolean DEFAULT false NOT NULL
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: vote; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vote (
    id integer NOT NULL,
    vote_id integer NOT NULL,
    general_id integer NOT NULL,
    nation_id integer NOT NULL,
    selection jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: vote_comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vote_comment (
    id integer NOT NULL,
    vote_id integer NOT NULL,
    general_id integer NOT NULL,
    nation_id integer NOT NULL,
    general_name text NOT NULL,
    nation_name text NOT NULL,
    text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: vote_comment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.vote_comment_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: vote_comment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.vote_comment_id_seq OWNED BY public.vote_comment.id;


--
-- Name: vote_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.vote_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: vote_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.vote_id_seq OWNED BY public.vote.id;


--
-- Name: vote_poll; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vote_poll (
    id integer NOT NULL,
    title text NOT NULL,
    body text DEFAULT ''::text NOT NULL,
    options jsonb NOT NULL,
    multiple_options integer DEFAULT 1 NOT NULL,
    reveal_mode text NOT NULL,
    opener_general_id integer NOT NULL,
    opener_name text NOT NULL,
    start_at timestamp with time zone DEFAULT now() NOT NULL,
    end_at timestamp with time zone,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: vote_poll_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.vote_poll_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: vote_poll_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.vote_poll_id_seq OWNED BY public.vote_poll.id;


--
-- Name: world_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.world_state (
    id integer NOT NULL,
    scenario_code text NOT NULL,
    current_year integer NOT NULL,
    current_month integer NOT NULL,
    tick_seconds integer NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    start_year integer,
    start_time timestamp with time zone,
    turn_term integer,
    isunited integer DEFAULT 0 NOT NULL,
    hidden_seed text,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    current_phase integer DEFAULT 1 NOT NULL,
    world_version bigint DEFAULT 0 NOT NULL,
    writer_epoch bigint DEFAULT 0 NOT NULL,
    CONSTRAINT world_state_current_phase_chk CHECK (((current_phase >= 1) AND (current_phase <= 3))),
    CONSTRAINT world_state_id_positive_check CHECK ((id > 0))
);


--
-- Name: world_state_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.world_state_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: world_state_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.world_state_id_seq OWNED BY public.world_state.id;


--
-- Name: yearbook_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.yearbook_history (
    id integer NOT NULL,
    profile_name text NOT NULL,
    year integer NOT NULL,
    month integer NOT NULL,
    map jsonb NOT NULL,
    nations jsonb NOT NULL,
    hash text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    global_history jsonb DEFAULT '[]'::jsonb NOT NULL,
    global_action jsonb DEFAULT '[]'::jsonb NOT NULL,
    server_id text NOT NULL,
    world_id integer NOT NULL
);


--
-- Name: yearbook_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.yearbook_history_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: yearbook_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.yearbook_history_id_seq OWNED BY public.yearbook_history.id;


--
-- Name: banned_member id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banned_member ALTER COLUMN id SET DEFAULT nextval('public.banned_member_id_seq'::regclass);


--
-- Name: board_comment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_comment ALTER COLUMN id SET DEFAULT nextval('public.board_comment_id_seq'::regclass);


--
-- Name: board_post id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post ALTER COLUMN id SET DEFAULT nextval('public.board_post_id_seq'::regclass);


--
-- Name: diplomacy id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy ALTER COLUMN id SET DEFAULT nextval('public.diplomacy_id_seq'::regclass);


--
-- Name: diplomacy_letter id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy_letter ALTER COLUMN id SET DEFAULT nextval('public.diplomacy_letter_id_seq'::regclass);


--
-- Name: emperior id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emperior ALTER COLUMN id SET DEFAULT nextval('public.emperior_id_seq'::regclass);


--
-- Name: error_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.error_log ALTER COLUMN id SET DEFAULT nextval('public.error_log_id_seq'::regclass);


--
-- Name: event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event ALTER COLUMN id SET DEFAULT nextval('public.event_id_seq'::regclass);


--
-- Name: game_kv id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_kv ALTER COLUMN id SET DEFAULT nextval('public.game_kv_id_seq'::regclass);


--
-- Name: general_access_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_access_log ALTER COLUMN id SET DEFAULT nextval('public.general_access_log_id_seq'::regclass);


--
-- Name: general_turn id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_turn ALTER COLUMN id SET DEFAULT nextval('public.general_turn_id_seq'::regclass);


--
-- Name: hall id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hall ALTER COLUMN id SET DEFAULT nextval('public.hall_id_seq'::regclass);


--
-- Name: inheritance_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_log ALTER COLUMN id SET DEFAULT nextval('public.inheritance_log_id_seq'::regclass);


--
-- Name: inheritance_point id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_point ALTER COLUMN id SET DEFAULT nextval('public.inheritance_point_id_seq'::regclass);


--
-- Name: inheritance_result id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_result ALTER COLUMN id SET DEFAULT nextval('public.inheritance_result_id_seq'::regclass);


--
-- Name: log_entry id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_entry ALTER COLUMN id SET DEFAULT nextval('public.log_entry_id_seq'::regclass);


--
-- Name: message id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message ALTER COLUMN id SET DEFAULT nextval('public.message_id_seq'::regclass);


--
-- Name: nation_env id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_env ALTER COLUMN id SET DEFAULT nextval('public.nation_env_id_seq'::regclass);


--
-- Name: nation_turn id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_turn ALTER COLUMN id SET DEFAULT nextval('public.nation_turn_id_seq'::regclass);


--
-- Name: ng_auction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction ALTER COLUMN id SET DEFAULT nextval('public.ng_auction_id_seq'::regclass);


--
-- Name: ng_auction_bid no; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid ALTER COLUMN no SET DEFAULT nextval('public.ng_auction_bid_no_seq'::regclass);


--
-- Name: ng_betting id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_betting ALTER COLUMN id SET DEFAULT nextval('public.ng_betting_id_seq'::regclass);


--
-- Name: ng_games id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_games ALTER COLUMN id SET DEFAULT nextval('public.ng_games_id_seq'::regclass);


--
-- Name: ng_old_generals id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_generals ALTER COLUMN id SET DEFAULT nextval('public.ng_old_generals_id_seq'::regclass);


--
-- Name: ng_old_nations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_nations ALTER COLUMN id SET DEFAULT nextval('public.ng_old_nations_id_seq'::regclass);


--
-- Name: rank_data id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rank_data ALTER COLUMN id SET DEFAULT nextval('public.rank_data_id_seq'::regclass);


--
-- Name: select_npc_token id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_npc_token ALTER COLUMN id SET DEFAULT nextval('public.select_npc_token_id_seq'::regclass);


--
-- Name: select_pool id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_pool ALTER COLUMN id SET DEFAULT nextval('public.select_pool_id_seq'::regclass);


--
-- Name: statistic id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.statistic ALTER COLUMN id SET DEFAULT nextval('public.statistic_id_seq'::regclass);


--
-- Name: system_flag id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_flag ALTER COLUMN id SET DEFAULT nextval('public.system_flag_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: vote id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote ALTER COLUMN id SET DEFAULT nextval('public.vote_id_seq'::regclass);


--
-- Name: vote_comment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_comment ALTER COLUMN id SET DEFAULT nextval('public.vote_comment_id_seq'::regclass);


--
-- Name: vote_poll id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_poll ALTER COLUMN id SET DEFAULT nextval('public.vote_poll_id_seq'::regclass);


--
-- Name: world_state id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.world_state ALTER COLUMN id SET DEFAULT nextval('public.world_state_id_seq'::regclass);


--
-- Name: yearbook_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yearbook_history ALTER COLUMN id SET DEFAULT nextval('public.yearbook_history_id_seq'::regclass);


--
-- Name: banned_member banned_member_hashed_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banned_member
    ADD CONSTRAINT banned_member_hashed_email_key UNIQUE (hashed_email);


--
-- Name: banned_member banned_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.banned_member
    ADD CONSTRAINT banned_member_pkey PRIMARY KEY (id);


--
-- Name: board_comment board_comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_comment
    ADD CONSTRAINT board_comment_pkey PRIMARY KEY (world_id, id);


--
-- Name: board_post board_post_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post
    ADD CONSTRAINT board_post_pkey PRIMARY KEY (world_id, id);


--
-- Name: city city_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.city
    ADD CONSTRAINT city_pkey PRIMARY KEY (world_id, id);


--
-- Name: command_inbox command_inbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_inbox
    ADD CONSTRAINT command_inbox_pkey PRIMARY KEY (world_id, request_id);


--
-- Name: command_outbox command_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_outbox
    ADD CONSTRAINT command_outbox_pkey PRIMARY KEY (world_id, event_id);


--
-- Name: command_result command_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_result
    ADD CONSTRAINT command_result_pkey PRIMARY KEY (world_id, request_id, result_seq);


--
-- Name: diplomacy_letter diplomacy_letter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy_letter
    ADD CONSTRAINT diplomacy_letter_pkey PRIMARY KEY (world_id, id);


--
-- Name: diplomacy diplomacy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy
    ADD CONSTRAINT diplomacy_pkey PRIMARY KEY (world_id, id);


--
-- Name: diplomacy diplomacy_world_src_dest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy
    ADD CONSTRAINT diplomacy_world_src_dest_key UNIQUE (world_id, src_nation_id, dest_nation_id);


--
-- Name: emperior emperior_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emperior
    ADD CONSTRAINT emperior_pkey PRIMARY KEY (world_id, id);


--
-- Name: error_log error_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.error_log
    ADD CONSTRAINT error_log_pkey PRIMARY KEY (id);


--
-- Name: event event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event
    ADD CONSTRAINT event_pkey PRIMARY KEY (world_id, id);


--
-- Name: game_kv game_kv_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_kv
    ADD CONSTRAINT game_kv_pkey PRIMARY KEY (id);


--
-- Name: general_access_log general_access_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_access_log
    ADD CONSTRAINT general_access_log_pkey PRIMARY KEY (world_id, id);


--
-- Name: general_access_log general_access_log_world_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_access_log
    ADD CONSTRAINT general_access_log_world_general_key UNIQUE (world_id, general_id);


--
-- Name: general general_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general
    ADD CONSTRAINT general_pkey PRIMARY KEY (world_id, id);


--
-- Name: general_turn general_turn_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_turn
    ADD CONSTRAINT general_turn_pkey PRIMARY KEY (world_id, id);


--
-- Name: general_turn general_turn_world_id_general_id_turn_idx_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_turn
    ADD CONSTRAINT general_turn_world_id_general_id_turn_idx_key UNIQUE (world_id, general_id, turn_idx);


--
-- Name: hall hall_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hall
    ADD CONSTRAINT hall_pkey PRIMARY KEY (world_id, id);


--
-- Name: hall hall_world_owner_server_type_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hall
    ADD CONSTRAINT hall_world_owner_server_type_key UNIQUE (world_id, owner, server_id, type);


--
-- Name: hall hall_world_server_type_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hall
    ADD CONSTRAINT hall_world_server_type_general_key UNIQUE (world_id, server_id, type, general_no);


--
-- Name: inheritance_log inheritance_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_log
    ADD CONSTRAINT inheritance_log_pkey PRIMARY KEY (id);


--
-- Name: inheritance_point inheritance_point_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_point
    ADD CONSTRAINT inheritance_point_pkey PRIMARY KEY (id);


--
-- Name: inheritance_point inheritance_point_user_id_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_point
    ADD CONSTRAINT inheritance_point_user_id_key_key UNIQUE (user_id, key);


--
-- Name: inheritance_result inheritance_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_result
    ADD CONSTRAINT inheritance_result_pkey PRIMARY KEY (world_id, id);


--
-- Name: inheritance_user_state inheritance_user_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_user_state
    ADD CONSTRAINT inheritance_user_state_pkey PRIMARY KEY (user_id);


--
-- Name: log_entry log_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_entry
    ADD CONSTRAINT log_entry_pkey PRIMARY KEY (world_id, id);


--
-- Name: message message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message
    ADD CONSTRAINT message_pkey PRIMARY KEY (world_id, id);


--
-- Name: nation_env nation_env_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_env
    ADD CONSTRAINT nation_env_pkey PRIMARY KEY (world_id, id);


--
-- Name: nation_env nation_env_world_namespace_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_env
    ADD CONSTRAINT nation_env_world_namespace_key UNIQUE (world_id, namespace, key);


--
-- Name: nation nation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation
    ADD CONSTRAINT nation_pkey PRIMARY KEY (world_id, id);


--
-- Name: nation_turn nation_turn_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_turn
    ADD CONSTRAINT nation_turn_pkey PRIMARY KEY (world_id, id);


--
-- Name: nation_turn nation_turn_world_id_nation_id_officer_level_turn_idx_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_turn
    ADD CONSTRAINT nation_turn_world_id_nation_id_officer_level_turn_idx_key UNIQUE (world_id, nation_id, officer_level, turn_idx);


--
-- Name: ng_auction_bid ng_auction_bid_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_pkey PRIMARY KEY (world_id, no);


--
-- Name: ng_auction_bid ng_auction_bid_world_auction_amount_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_world_auction_amount_key UNIQUE (world_id, auction_id, amount);


--
-- Name: ng_auction_bid ng_auction_bid_world_general_auction_amount_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_world_general_auction_amount_key UNIQUE (world_id, general_id, auction_id, amount);


--
-- Name: ng_auction ng_auction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction
    ADD CONSTRAINT ng_auction_pkey PRIMARY KEY (world_id, id);


--
-- Name: ng_betting ng_betting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_betting
    ADD CONSTRAINT ng_betting_pkey PRIMARY KEY (world_id, id);


--
-- Name: ng_betting ng_betting_world_betting_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_betting
    ADD CONSTRAINT ng_betting_world_betting_general_key UNIQUE (world_id, betting_id, betting_type, general_id);


--
-- Name: ng_betting ng_betting_world_general_betting_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_betting
    ADD CONSTRAINT ng_betting_world_general_betting_key UNIQUE (world_id, general_id, betting_id, betting_type);


--
-- Name: ng_games ng_games_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_games
    ADD CONSTRAINT ng_games_pkey PRIMARY KEY (world_id, id);


--
-- Name: ng_games ng_games_world_server_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_games
    ADD CONSTRAINT ng_games_world_server_key UNIQUE (world_id, server_id);


--
-- Name: ng_old_generals ng_old_generals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_generals
    ADD CONSTRAINT ng_old_generals_pkey PRIMARY KEY (world_id, id);


--
-- Name: ng_old_generals ng_old_generals_world_server_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_generals
    ADD CONSTRAINT ng_old_generals_world_server_general_key UNIQUE (world_id, server_id, general_no);


--
-- Name: ng_old_nations ng_old_nations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_nations
    ADD CONSTRAINT ng_old_nations_pkey PRIMARY KEY (world_id, id);


--
-- Name: ng_old_nations ng_old_nations_world_server_nation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_nations
    ADD CONSTRAINT ng_old_nations_world_server_nation_key UNIQUE (world_id, server_id, nation);


--
-- Name: general_owner pk_general_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_owner
    ADD CONSTRAINT pk_general_owner PRIMARY KEY (world_id, general_id);


--
-- Name: rank_data rank_data_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rank_data
    ADD CONSTRAINT rank_data_pkey PRIMARY KEY (world_id, id);


--
-- Name: rank_data rank_data_world_general_type_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rank_data
    ADD CONSTRAINT rank_data_world_general_type_key UNIQUE (world_id, general_id, type);


--
-- Name: select_npc_token select_npc_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_npc_token
    ADD CONSTRAINT select_npc_token_pkey PRIMARY KEY (world_id, id);


--
-- Name: select_pool select_pool_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_pool
    ADD CONSTRAINT select_pool_pkey PRIMARY KEY (world_id, id);


--
-- Name: select_pool select_pool_world_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_pool
    ADD CONSTRAINT select_pool_world_general_key UNIQUE (world_id, general_id);


--
-- Name: select_pool select_pool_world_unique_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_pool
    ADD CONSTRAINT select_pool_world_unique_name_key UNIQUE (world_id, unique_name);


--
-- Name: statistic statistic_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.statistic
    ADD CONSTRAINT statistic_pkey PRIMARY KEY (world_id, id);


--
-- Name: system_flag system_flag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_flag
    ADD CONSTRAINT system_flag_pkey PRIMARY KEY (id);


--
-- Name: troop troop_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.troop
    ADD CONSTRAINT troop_pkey PRIMARY KEY (world_id, troop_leader);


--
-- Name: general_owner uq_general_owner_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_owner
    ADD CONSTRAINT uq_general_owner_user UNIQUE (world_id, user_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: vote_comment vote_comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_comment
    ADD CONSTRAINT vote_comment_pkey PRIMARY KEY (world_id, id);


--
-- Name: vote vote_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote
    ADD CONSTRAINT vote_pkey PRIMARY KEY (world_id, id);


--
-- Name: vote_poll vote_poll_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_poll
    ADD CONSTRAINT vote_poll_pkey PRIMARY KEY (world_id, id);


--
-- Name: vote vote_world_poll_general_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote
    ADD CONSTRAINT vote_world_poll_general_key UNIQUE (world_id, vote_id, general_id);


--
-- Name: world_state world_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.world_state
    ADD CONSTRAINT world_state_pkey PRIMARY KEY (id);


--
-- Name: yearbook_history yearbook_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yearbook_history
    ADD CONSTRAINT yearbook_history_pkey PRIMARY KEY (world_id, id);


--
-- Name: board_comment_post_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_comment_post_idx ON public.board_comment USING btree (world_id, post_id, created_at);


--
-- Name: board_post_nation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_post_nation_idx ON public.board_post USING btree (world_id, nation_id, is_secret, created_at);


--
-- Name: diplomacy_letter_dest_src_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX diplomacy_letter_dest_src_idx ON public.diplomacy_letter USING btree (world_id, dest_nation_id, src_nation_id);


--
-- Name: diplomacy_letter_src_dest_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX diplomacy_letter_src_dest_idx ON public.diplomacy_letter USING btree (world_id, src_nation_id, dest_nation_id);


--
-- Name: diplomacy_letter_state_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX diplomacy_letter_state_date_idx ON public.diplomacy_letter USING btree (world_id, state, date);


--
-- Name: diplomacy_world_dest_src_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX diplomacy_world_dest_src_idx ON public.diplomacy USING btree (world_id, dest_nation_id, src_nation_id);


--
-- Name: emperior_server_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX emperior_server_idx ON public.emperior USING btree (world_id, server_id, id);


--
-- Name: error_log_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX error_log_category_idx ON public.error_log USING btree (category, id);


--
-- Name: event_target_priority_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX event_target_priority_id_idx ON public.event USING btree (world_id, target_code, priority DESC, id);


--
-- Name: game_kv_inheritance_global_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX game_kv_inheritance_global_key_uq ON public.game_kv USING btree ("table", namespace, key) WHERE (("table" = 'inheritance'::text) AND (world_id IS NULL));


--
-- Name: game_kv_world_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX game_kv_world_key_uq ON public.game_kv USING btree (world_id, "table", namespace, key) WHERE (("table" <> 'inheritance'::text) AND (world_id IS NOT NULL));


--
-- Name: general_access_log_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX general_access_log_user_idx ON public.general_access_log USING btree (world_id, user_id);


--
-- Name: hall_scenario; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX hall_scenario ON public.hall USING btree (world_id, season, scenario, type, value);


--
-- Name: hall_server_show; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX hall_server_show ON public.hall USING btree (world_id, server_id, type, value);


--
-- Name: idx_command_inbox_world_claimable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_command_inbox_world_claimable ON public.command_inbox USING btree (world_id, status, created_at);


--
-- Name: idx_command_inbox_world_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_command_inbox_world_status_created ON public.command_inbox USING btree (world_id, status, created_at);


--
-- Name: idx_command_outbox_world_unpublished; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_command_outbox_world_unpublished ON public.command_outbox USING btree (world_id, created_at) WHERE (published_at IS NULL);


--
-- Name: idx_command_result_world_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_command_result_world_created ON public.command_result USING btree (world_id, created_at);


--
-- Name: idx_general_turn_world_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_general_turn_world_request ON public.general_turn USING btree (world_id, request_id) WHERE (request_id IS NOT NULL);


--
-- Name: idx_nation_turn_world_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nation_turn_world_request ON public.nation_turn USING btree (world_id, request_id) WHERE (request_id IS NOT NULL);


--
-- Name: idx_select_npc_token_owner_valid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_select_npc_token_owner_valid ON public.select_npc_token USING btree (world_id, owner_id, valid_until);


--
-- Name: idx_select_npc_token_valid_until; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_select_npc_token_valid_until ON public.select_npc_token USING btree (world_id, valid_until);


--
-- Name: idx_users_delete_after; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_delete_after ON public.users USING btree (delete_after);


--
-- Name: idx_users_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_username ON public.users USING btree (username);


--
-- Name: inheritance_log_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inheritance_log_user_idx ON public.inheritance_log USING btree (user_id, id);


--
-- Name: inheritance_point_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inheritance_point_user_idx ON public.inheritance_point USING btree (user_id);


--
-- Name: inheritance_result_server_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inheritance_result_server_owner_idx ON public.inheritance_result USING btree (world_id, server_id, owner);


--
-- Name: log_entry_general_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX log_entry_general_idx ON public.log_entry USING btree (world_id, general_id, category, id);


--
-- Name: log_entry_nation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX log_entry_nation_idx ON public.log_entry USING btree (world_id, nation_id, category, id);


--
-- Name: log_entry_scope_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX log_entry_scope_idx ON public.log_entry USING btree (world_id, scope, category, id);


--
-- Name: log_entry_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX log_entry_user_idx ON public.log_entry USING btree (world_id, user_id, category, id);


--
-- Name: log_entry_year_month_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id);


--
-- Name: message_by_mailbox; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_by_mailbox ON public.message USING btree (world_id, mailbox, type, id);


--
-- Name: nation_env_by_namespace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX nation_env_by_namespace ON public.nation_env USING btree (world_id, namespace);


--
-- Name: ng_auction_by_close; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_auction_by_close ON public.ng_auction USING btree (world_id, finished, type, close_date);


--
-- Name: ng_auction_by_general; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_auction_by_general ON public.ng_auction USING btree (world_id, host_general_id, type, finished);


--
-- Name: ng_betting_by_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_betting_by_user ON public.ng_betting USING btree (world_id, user_id, betting_id, betting_type);


--
-- Name: ng_games_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_games_date_idx ON public.ng_games USING btree (world_id, date);


--
-- Name: ng_old_generals_by_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_old_generals_by_name ON public.ng_old_generals USING btree (world_id, server_id, name);


--
-- Name: ng_old_generals_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ng_old_generals_owner ON public.ng_old_generals USING btree (world_id, owner, server_id);


--
-- Name: rank_data_by_nation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX rank_data_by_nation ON public.rank_data USING btree (world_id, nation_id, type, value);


--
-- Name: rank_data_by_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX rank_data_by_type ON public.rank_data USING btree (world_id, type, value);


--
-- Name: select_pool_owner_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX select_pool_owner_idx ON public.select_pool USING btree (world_id, owner);


--
-- Name: select_pool_reserved_until_general_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX select_pool_reserved_until_general_idx ON public.select_pool USING btree (world_id, reserved_until, general_id);


--
-- Name: statistic_world_year_month_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX statistic_world_year_month_idx ON public.statistic USING btree (world_id, year, month, id);


--
-- Name: troop_world_nation_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX troop_world_nation_idx ON public.troop USING btree (world_id, nation, troop_leader);


--
-- Name: vote_comment_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX vote_comment_idx ON public.vote_comment USING btree (world_id, vote_id, created_at);


--
-- Name: vote_poll_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX vote_poll_idx ON public.vote USING btree (world_id, vote_id);


--
-- Name: yearbook_history_server_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX yearbook_history_server_idx ON public.yearbook_history USING btree (world_id, server_id, year, month, id);


--
-- Name: board_comment board_comment_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_comment
    ADD CONSTRAINT board_comment_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: board_comment board_comment_world_post_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_comment
    ADD CONSTRAINT board_comment_world_post_fkey FOREIGN KEY (world_id, post_id) REFERENCES public.board_post(world_id, id) ON DELETE CASCADE;


--
-- Name: board_post board_post_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post
    ADD CONSTRAINT board_post_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: city city_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.city
    ADD CONSTRAINT city_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: command_inbox command_inbox_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_inbox
    ADD CONSTRAINT command_inbox_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: command_outbox command_outbox_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_outbox
    ADD CONSTRAINT command_outbox_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: command_result command_result_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_result
    ADD CONSTRAINT command_result_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: diplomacy_letter diplomacy_letter_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy_letter
    ADD CONSTRAINT diplomacy_letter_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: diplomacy_letter diplomacy_letter_world_prev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy_letter
    ADD CONSTRAINT diplomacy_letter_world_prev_fkey FOREIGN KEY (world_id, prev_id) REFERENCES public.diplomacy_letter(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: diplomacy diplomacy_world_dest_nation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy
    ADD CONSTRAINT diplomacy_world_dest_nation_fkey FOREIGN KEY (world_id, dest_nation_id) REFERENCES public.nation(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: diplomacy diplomacy_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy
    ADD CONSTRAINT diplomacy_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: diplomacy diplomacy_world_src_nation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.diplomacy
    ADD CONSTRAINT diplomacy_world_src_nation_fkey FOREIGN KEY (world_id, src_nation_id) REFERENCES public.nation(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: emperior emperior_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emperior
    ADD CONSTRAINT emperior_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: event event_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event
    ADD CONSTRAINT event_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: game_kv game_kv_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_kv
    ADD CONSTRAINT game_kv_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: general_access_log general_access_log_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_access_log
    ADD CONSTRAINT general_access_log_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: general_owner general_owner_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_owner
    ADD CONSTRAINT general_owner_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: general_turn general_turn_world_general_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_turn
    ADD CONSTRAINT general_turn_world_general_fkey FOREIGN KEY (world_id, general_id) REFERENCES public.general(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: general_turn general_turn_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general_turn
    ADD CONSTRAINT general_turn_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: general general_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.general
    ADD CONSTRAINT general_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: hall hall_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hall
    ADD CONSTRAINT hall_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: inheritance_result inheritance_result_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inheritance_result
    ADD CONSTRAINT inheritance_result_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: log_entry log_entry_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_entry
    ADD CONSTRAINT log_entry_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: message message_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message
    ADD CONSTRAINT message_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: nation_env nation_env_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_env
    ADD CONSTRAINT nation_env_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: nation_turn nation_turn_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_turn
    ADD CONSTRAINT nation_turn_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: nation_turn nation_turn_world_nation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation_turn
    ADD CONSTRAINT nation_turn_world_nation_fkey FOREIGN KEY (world_id, nation_id) REFERENCES public.nation(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: nation nation_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nation
    ADD CONSTRAINT nation_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_auction_bid ng_auction_bid_world_auction_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_world_auction_fkey FOREIGN KEY (world_id, auction_id) REFERENCES public.ng_auction(world_id, id);


--
-- Name: ng_auction_bid ng_auction_bid_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_auction ng_auction_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_auction
    ADD CONSTRAINT ng_auction_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_betting ng_betting_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_betting
    ADD CONSTRAINT ng_betting_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_games ng_games_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_games
    ADD CONSTRAINT ng_games_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_old_generals ng_old_generals_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_generals
    ADD CONSTRAINT ng_old_generals_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: ng_old_nations ng_old_nations_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ng_old_nations
    ADD CONSTRAINT ng_old_nations_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: rank_data rank_data_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rank_data
    ADD CONSTRAINT rank_data_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: select_npc_token select_npc_token_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_npc_token
    ADD CONSTRAINT select_npc_token_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: select_pool select_pool_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.select_pool
    ADD CONSTRAINT select_pool_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: statistic statistic_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.statistic
    ADD CONSTRAINT statistic_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: troop troop_world_general_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.troop
    ADD CONSTRAINT troop_world_general_fkey FOREIGN KEY (world_id, troop_leader) REFERENCES public.general(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: troop troop_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.troop
    ADD CONSTRAINT troop_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: troop troop_world_nation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.troop
    ADD CONSTRAINT troop_world_nation_fkey FOREIGN KEY (world_id, nation) REFERENCES public.nation(world_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: vote_comment vote_comment_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_comment
    ADD CONSTRAINT vote_comment_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: vote_comment vote_comment_world_poll_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_comment
    ADD CONSTRAINT vote_comment_world_poll_fkey FOREIGN KEY (world_id, vote_id) REFERENCES public.vote_poll(world_id, id) ON DELETE CASCADE;


--
-- Name: vote_poll vote_poll_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote_poll
    ADD CONSTRAINT vote_poll_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: vote vote_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote
    ADD CONSTRAINT vote_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
-- Name: vote vote_world_poll_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vote
    ADD CONSTRAINT vote_world_poll_fkey FOREIGN KEY (world_id, vote_id) REFERENCES public.vote_poll(world_id, id) ON DELETE CASCADE;


--
-- Name: yearbook_history yearbook_history_world_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.yearbook_history
    ADD CONSTRAINT yearbook_history_world_id_fkey FOREIGN KEY (world_id) REFERENCES public.world_state(id);


--
--


