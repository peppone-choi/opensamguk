CREATE TABLE select_npc_token (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    pick_more_from TIMESTAMPTZ NOT NULL,
    pick_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    nonce INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_select_npc_token_owner_valid
    ON select_npc_token (owner_id, valid_until);

CREATE INDEX idx_select_npc_token_valid_until
    ON select_npc_token (valid_until);
