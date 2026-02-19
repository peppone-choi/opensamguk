ALTER TABLE board_comment
    DROP CONSTRAINT IF EXISTS board_comment_board_id_fkey;

ALTER TABLE board_comment
    ADD CONSTRAINT board_comment_board_id_fkey
    FOREIGN KEY (board_id) REFERENCES message(id) ON DELETE CASCADE NOT VALID;
