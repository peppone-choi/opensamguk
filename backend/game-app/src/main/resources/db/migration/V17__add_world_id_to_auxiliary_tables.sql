-- Add world_id FK to auxiliary child tables for logical isolation

-- auction_bid: derive world_id from parent auction
ALTER TABLE auction_bid ADD COLUMN world_id BIGINT;
UPDATE auction_bid SET world_id = (SELECT a.world_id FROM auction a WHERE a.id = auction_bid.auction_id);
ALTER TABLE auction_bid ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE auction_bid ADD CONSTRAINT fk_auction_bid_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
CREATE INDEX idx_auction_bid_world_id ON auction_bid(world_id);

-- bet_entry: derive world_id from parent betting
ALTER TABLE bet_entry ADD COLUMN world_id BIGINT;
UPDATE bet_entry SET world_id = (SELECT b.world_id FROM betting b WHERE b.id = bet_entry.betting_id);
ALTER TABLE bet_entry ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE bet_entry ADD CONSTRAINT fk_bet_entry_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
CREATE INDEX idx_bet_entry_world_id ON bet_entry(world_id);

-- board_comment: derive world_id from parent board
ALTER TABLE board_comment ADD COLUMN world_id BIGINT;
UPDATE board_comment SET world_id = (SELECT b.world_id FROM board b WHERE b.id = board_comment.board_id);
ALTER TABLE board_comment ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE board_comment ADD CONSTRAINT fk_board_comment_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
CREATE INDEX idx_board_comment_world_id ON board_comment(world_id);

-- vote_cast: derive world_id from parent vote
ALTER TABLE vote_cast ADD COLUMN world_id BIGINT;
UPDATE vote_cast SET world_id = (SELECT v.world_id FROM vote v WHERE v.id = vote_cast.vote_id);
ALTER TABLE vote_cast ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE vote_cast ADD CONSTRAINT fk_vote_cast_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
CREATE INDEX idx_vote_cast_world_id ON vote_cast(world_id);

-- nation_flag: derive world_id from parent nation
ALTER TABLE nation_flag ADD COLUMN world_id BIGINT;
UPDATE nation_flag SET world_id = (SELECT n.world_id FROM nation n WHERE n.id = nation_flag.nation_id);
ALTER TABLE nation_flag ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE nation_flag ADD CONSTRAINT fk_nation_flag_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
CREATE INDEX idx_nation_flag_world_id ON nation_flag(world_id);
