-- C-3b receipt acceptance is not an executed v1 combat event. V80 belongs to campaign origin.
ALTER TABLE battle_command_receipt ADD COLUMN v2_command boolean NOT NULL DEFAULT false;

ALTER TABLE battle_command_receipt DROP CONSTRAINT battle_command_receipt_verdict_ck;
ALTER TABLE battle_command_receipt ADD CONSTRAINT battle_command_receipt_verdict_ck CHECK (
    (verdict = 'ACCEPTED' AND reason_code IS NULL AND (
        (v2_command AND deployment_revision_after IS NULL AND effective_tick IS NULL AND event_seq IS NULL)
        OR (NOT v2_command AND deployment_revision_after IS NULL
            AND effective_tick IS NOT NULL AND event_seq IS NOT NULL)
        OR (NOT v2_command AND deployment_revision_after IS NOT NULL AND server_tick = 0
            AND effective_tick IS NULL AND event_seq IS NULL)))
    OR (verdict = 'REJECTED' AND reason_code IS NOT NULL AND effective_tick IS NULL
        AND event_seq IS NULL AND deployment_revision_after IS NULL)
);
