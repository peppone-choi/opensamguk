ALTER TABLE auction
    ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'item',
    ADD COLUMN IF NOT EXISTS sub_type TEXT,
    ADD COLUMN IF NOT EXISTS amount INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS start_bid_amount INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS finish_bid_amount INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS host_general_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS host_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS close_date_extension_count INTEGER NOT NULL DEFAULT 3;

UPDATE auction
SET host_general_id = seller_general_id
WHERE host_general_id = 0;

UPDATE auction
SET start_bid_amount = min_price
WHERE start_bid_amount = 0;
