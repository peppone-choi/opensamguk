-- 닉네임을 공개 표시 이름으로 승격한다: 공백 정리 → 빈 값 백필 → 중복 해소 → NOT NULL → UNIQUE.
--
-- 순서에 이유가 있다. 백필을 먼저 하면 사용자가 **직접 정한** 닉네임이 남의 아이디에서
-- 백필된 값과 부딪혀 밀려날 수 있다 — 그래서 백필 대상을 미리 표시해 두고, 중복이 나면
-- 백필된 쪽만 옮긴다.

-- 0) 백필 대상(=사용자가 정한 적 없는 행)을 먼저 기억한다.
CREATE TEMP TABLE v42_backfilled ON COMMIT DROP AS
SELECT id FROM users WHERE nickname IS NULL OR btrim(nickname) = '';

-- 1) 앞뒤 공백을 떼어낸다. 'bob ' 과 'bob' 이 다른 이름으로 남으면 사칭 방지가 무의미해진다.
UPDATE users SET nickname = btrim(nickname) WHERE nickname IS NOT NULL AND nickname <> btrim(nickname);

-- 2) 닉네임이 없던 기존 계정은 아이디를 그대로 표시 이름으로 쓴다(표시가 비는 것보다 낫다).
--    아이디는 UNIQUE 라 백필끼리는 절대 부딪히지 않는다.
UPDATE users SET nickname = username WHERE id IN (SELECT id FROM v42_backfilled);

-- 3) UNIQUE 를 걸기 전에 중복을 떼어낸다. 대소문자만 다른 것도 같은 이름으로 본다.
--    첫 바퀴는 원래 이름에 '_<id>' 를 붙여 알아볼 수 있게 남기고, 그래도 부딪히면
--    'user_<id>' 로 떨어뜨린다 — 아이디는 행마다 유일하므로 반드시 끝난다.
DO $$
DECLARE
    moved integer;
    pass integer := 0;
BEGIN
    LOOP
        pass := pass + 1;
        UPDATE users u
        SET nickname = CASE WHEN pass = 1 THEN left(u.nickname, 30) || '_' || u.id
                            ELSE 'user_' || u.id END
        FROM (
            SELECT x.id,
                   row_number() OVER (
                       PARTITION BY lower(x.nickname)
                       -- 사용자가 정한 이름이 이긴다. 밀려나는 쪽은 백필된 행, 그 다음 후발 가입자.
                       ORDER BY x.backfilled ASC, x.id ASC
                   ) AS rn
            FROM (
                SELECT u2.id, u2.nickname, (b.id IS NOT NULL) AS backfilled
                FROM users u2 LEFT JOIN v42_backfilled b ON b.id = u2.id
            ) x
        ) d
        WHERE u.id = d.id AND d.rn > 1;
        GET DIAGNOSTICS moved = ROW_COUNT;
        EXIT WHEN moved = 0;
        IF pass > 10 THEN
            RAISE EXCEPTION 'V42: 닉네임 중복 해소가 수렴하지 않았다';
        END IF;
    END LOOP;
END $$;

-- 4) 이제 모든 행에 값이 있다 — 가입 필수화(RegisterRequest.nickname @NotBlank)와 짝을 맞춰 잠근다.
ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;

-- 5) 표시 이름이 유일해야 게시판에서 사칭이 안 된다. 대소문자 무시(= AuthService 의 중복 검사와 짝).
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_nickname ON users(lower(nickname));
