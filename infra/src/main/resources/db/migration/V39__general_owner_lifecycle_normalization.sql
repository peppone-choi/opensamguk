DELETE FROM general_owner AS owner_link
WHERE NOT EXISTS (
    SELECT 1
    FROM general AS body
    WHERE body.world_id = owner_link.world_id
      AND body.id = owner_link.general_id
);

DELETE FROM general_owner AS owner_link
USING general AS body
WHERE body.world_id = owner_link.world_id
  AND body.id = owner_link.general_id
  AND body.npc_state >= 2
  AND (
      owner_link.claim_request_id IS NULL
      OR EXISTS (
          SELECT 1
          FROM (
              SELECT command_result.*
              FROM command_result
              WHERE command_result.world_id = owner_link.world_id
                AND command_result.request_id = owner_link.claim_request_id
              ORDER BY command_result.result_seq DESC, command_result.created_at DESC
              LIMIT 1
          ) AS terminal_result
          WHERE terminal_result.terminal_status IN ('APPLIED', 'REJECTED')
            AND terminal_result.result_type = 'claimNpc'
            AND terminal_result.ok = (terminal_result.terminal_status = 'APPLIED')
            AND jsonb_typeof(terminal_result.result_payload) = 'object'
            AND jsonb_typeof(terminal_result.result_payload -> 'requestId') = 'string'
            AND terminal_result.result_payload ->> 'requestId' = owner_link.claim_request_id
            AND jsonb_typeof(terminal_result.result_payload -> 'sentAt') = 'string'
            AND (
                NOT (terminal_result.result_payload ? 'committedWorldVersion')
                OR terminal_result.result_payload -> 'committedWorldVersion' = 'null'::jsonb
                OR (
                    jsonb_typeof(terminal_result.result_payload -> 'committedWorldVersion') = 'number'
                    AND (
                        terminal_result.result_payload ->> 'committedWorldVersion' ~ '^-?[0-9]{1,18}$'
                        OR terminal_result.result_payload ->> 'committedWorldVersion' IN (
                            '9223372036854775807',
                            '-9223372036854775808'
                        )
                    )
                )
            )
            AND jsonb_typeof(terminal_result.result_payload -> 'event') = 'object'
            AND terminal_result.result_payload #>> '{event,type}' = 'commandResult'
            AND jsonb_typeof(terminal_result.result_payload #> '{event,result}') = 'object'
            AND terminal_result.result_payload #>> '{event,result,type}' = 'claimNpc'
            AND (
                NOT ((terminal_result.result_payload #> '{event,result}') ? 'reason')
                OR terminal_result.result_payload #> '{event,result,reason}' = 'null'::jsonb
                OR jsonb_typeof(terminal_result.result_payload #> '{event,result,reason}') = 'string'
            )
            AND jsonb_typeof(terminal_result.result_payload #> '{event,result,generalId}') = 'number'
            AND terminal_result.result_payload #>> '{event,result,generalId}' = owner_link.general_id::text
            AND jsonb_typeof(terminal_result.result_payload #> '{event,result,ok}') = 'boolean'
            AND terminal_result.result_payload #>> '{event,result,ok}' =
                CASE WHEN terminal_result.ok THEN 'true' ELSE 'false' END
      )
  );

UPDATE general
SET user_id = NULL
WHERE npc_state >= 2
  AND user_id IS NOT NULL;
