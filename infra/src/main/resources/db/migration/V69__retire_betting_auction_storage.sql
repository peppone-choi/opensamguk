-- Retired betting/auction persistence after the runtime write path was removed.
-- Keep the applied V1/V7/V31/V32 history unchanged; this migration advances existing databases.

DELETE FROM command_result AS result
WHERE result.result_payload::text LIKE '%NationBetting%'
   OR EXISTS (
       SELECT 1 FROM command_inbox AS inbox
       WHERE inbox.world_id = result.world_id
         AND inbox.request_id = result.request_id
         AND (
             inbox.action_code IN ('OpenNationBetting', 'FinishNationBetting')
             OR inbox.payload::text LIKE '%NationBetting%'
         )
   );

DELETE FROM command_outbox AS outbox
WHERE outbox.payload::text LIKE '%NationBetting%'
   OR outbox.event_type IN ('OpenNationBetting', 'FinishNationBetting')
   OR EXISTS (
       SELECT 1 FROM command_inbox AS inbox
       WHERE inbox.world_id = outbox.world_id
         AND inbox.request_id = outbox.request_id
         AND (
             inbox.action_code IN ('OpenNationBetting', 'FinishNationBetting')
             OR inbox.payload::text LIKE '%NationBetting%'
         )
   );

DELETE FROM command_inbox
WHERE action_code IN ('OpenNationBetting', 'FinishNationBetting')
   OR payload::text LIKE '%NationBetting%';

DELETE FROM event
WHERE action::text LIKE '%NationBetting%';

DELETE FROM game_kv
WHERE "table" = 'betting'
   OR ("table" = 'game_env' AND key = 'last_betting_id');

DROP TABLE ng_auction_bid;
DROP TABLE ng_auction;
DROP TABLE ng_betting;

DROP TYPE ng_auction_resource;
DROP TYPE ng_auction_type;
