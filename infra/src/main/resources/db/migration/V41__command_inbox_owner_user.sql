-- OPENSAM-197 — 명령 결과 조회 소유권 검사의 근거 열.
--
-- `GET /api/command/result/{requestId}`는 지금까지 경로 값만으로 결과를 돌려줬다. 즉 requestId를
-- 아는 사람이 곧 결과(성공 여부·deny 사유·payload)를 읽는 사람이었고, 보안이 값의 비밀성에만
-- 기대고 있었다. 소유자를 확인하려면 "누가 냈는가"가 인테이크 시점에 남아 있어야 한다.
--
-- `general_id`만으로는 부족하다: 장수선택(selectPoolPick/selectPoolUpdate)은 **아직 소유하지 않은**
-- 장수를 대상으로 제출하므로, 제출자 본인이 자기 결과를 못 읽게 된다. 그래서 제출한 계정을 따로 남긴다.
--
-- NULL 허용 = 이 마이그레이션 이전 행. 그 행들은 소유자를 확인할 수 없으므로 조회에서 거절된다
-- (폴링 창은 제출 직후 6초라 실사용 영향이 없다).
ALTER TABLE command_inbox
    ADD COLUMN IF NOT EXISTS owner_user_id integer;

COMMENT ON COLUMN command_inbox.owner_user_id IS
    'Submitting account (JWT subject) for result-read ownership checks (OPENSAM-197); NULL for pre-migration rows';
