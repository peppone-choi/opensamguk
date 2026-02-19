ALTER TABLE message
    ADD COLUMN IF NOT EXISTS mailbox_type TEXT;

UPDATE message
SET mailbox_type = CASE
    WHEN mailbox_code IN ('secret', 'national', 'nation') THEN 'NATIONAL'
    WHEN mailbox_code IN ('personal', 'message', 'private') THEN 'PRIVATE'
    WHEN mailbox_code IN ('diplomacy', 'diplomacy_letter') THEN 'DIPLOMACY'
    ELSE 'PUBLIC'
END
WHERE mailbox_type IS NULL;

ALTER TABLE message
    ALTER COLUMN mailbox_type SET DEFAULT 'PUBLIC';

ALTER TABLE message
    ALTER COLUMN mailbox_type SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_message_mailbox_type ON message(mailbox_type);
