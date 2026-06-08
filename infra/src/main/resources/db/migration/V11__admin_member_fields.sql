-- B0-DATA + B2b + B2e — 어드민 회원관리 기반 (legacy `member`/`system`/`banned_member` 패러티)
--
-- grand truth: legacy/devsam-core/f_install/sql/common_schema.sql
--   - `member`  (NO/oauth_type/PICTURE/IMGSVR/GRADE/BLOCK_DATE/delete_after …)
--   - `system`  (REG/LOGIN/NOTICE — 전역 가입/로그인 허용 플래그)
--   - `banned_member` (hashed_email = SHA512(salt|email|salt) 영구차단)
--
-- 권한 divergence(0.9.0): opensamguk는 `users.role ∈ {USER,ADMIN}` 단일 게이트를 유지한다.
-- legacy의 `GRADE` 0–9 다단계는 의도된 인증 divergence이므로 `grade` 컬럼은 nullable(미사용 기본 NULL)로
-- 추가만 해 둔다(1.0.0 멀티운영자 대비 — B-AUTH-EXT 백로그). 0.9.0 게이트는 여전히 role 기준.

-- ── B0-DATA: users 회원관리 컬럼 확장 (legacy `member` 대응) ──
ALTER TABLE users
    ADD COLUMN grade        INT          NULL,          -- legacy member.GRADE (divergence: nullable, 미사용)
    ADD COLUMN block_until  TIMESTAMPTZ  NULL,          -- legacy member.BLOCK_DATE (차단 만료)
    ADD COLUMN delete_after TIMESTAMPTZ  NULL,          -- legacy member.delete_after (탈퇴 예정)
    ADD COLUMN oauth_type   VARCHAR(16)  NULL,          -- legacy member.oauth_type ENUM('NONE','KAKAO')
    ADD COLUMN picture      VARCHAR(64)  NULL,          -- legacy member.PICTURE (장수 아이콘 파일)
    ADD COLUMN imgsvr       BOOLEAN      NOT NULL DEFAULT FALSE, -- legacy member.IMGSVR (이미지 서버 분기)
    ADD COLUMN last_login_at TIMESTAMPTZ NULL;          -- legacy member_log(action_type=login) 최신 대체

CREATE INDEX idx_users_delete_after ON users(delete_after);

-- ── B2b: system_flag — 전역 가입/로그인 허용 (legacy `system`.REG/LOGIN) ──
-- legacy는 'Y'/'N' VARCHAR(1). opensamguk은 boolean으로 정규화. NO=1 단일 행을 시드한다.
CREATE TABLE system_flag (
    id          BIGSERIAL    PRIMARY KEY,
    allow_join  BOOLEAN      NOT NULL DEFAULT FALSE,  -- legacy system.REG = 'Y'
    allow_login BOOLEAN      NOT NULL DEFAULT FALSE,  -- legacy system.LOGIN = 'Y'
    notice      VARCHAR(256) NOT NULL DEFAULT '',     -- legacy system.NOTICE
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 단일 시스템 행(legacy `WHERE NO = 1`). 기본값은 legacy DEFAULT 'N'(미허용)과 동일.
INSERT INTO system_flag (id, allow_join, allow_login)
VALUES (1, FALSE, FALSE);

-- ── B2e: banned_member — 영구 이메일 차단 (legacy `banned_member`) ──
-- hashed_email = lower(hex(SHA512(globalSalt | email | globalSalt))) — legacy BanEmailAddress.php:46
CREATE TABLE banned_member (
    id           BIGSERIAL     PRIMARY KEY,
    hashed_email VARCHAR(128)  NOT NULL UNIQUE,  -- SHA512(salt|email|salt) hex
    info         TEXT          NULL,             -- 부가정보(등록 시각 등)
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
