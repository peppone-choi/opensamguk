-- 닉네임을 공개 표시 이름으로 승격한다: 빈 값 백필 → 중복 해소 → NOT NULL → UNIQUE.

-- 1) 닉네임이 없던 기존 계정은 아이디를 그대로 표시 이름으로 쓴다(표시가 비는 것보다 낫다).
UPDATE users SET nickname = username WHERE nickname IS NULL OR btrim(nickname) = '';

-- 2) UNIQUE 를 걸기 전에 중복을 떼어낸다. id 오름차순으로 먼저 만든 계정이 원래 닉네임을 지키고,
--    뒤에 만든 계정만 '_<id>' 접미사를 받는다. 이 단계를 빼면 3·4단계가 기존 DB에서 실패한다.
UPDATE users u
SET nickname = left(u.nickname, 40) || '_' || u.id
FROM (
    SELECT id, row_number() OVER (PARTITION BY nickname ORDER BY id) AS rn FROM users
) d
WHERE u.id = d.id AND d.rn > 1;

-- 3) 이제 모든 행에 값이 있다 — 가입 필수화(RegisterRequest.nickname @NotBlank)와 짝을 맞춰 잠근다.
ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;

-- 4) 표시 이름이 유일해야 게시판에서 사칭이 안 된다. 재실행 대비 IF NOT EXISTS.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_nickname ON users(nickname);
