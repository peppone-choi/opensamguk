# Parity Audit V3 — Batch 1 (Auth / Lobby / Admin)

Compared new `frontend/` pages against `legacy/hwe/ts/` code. Only **missing features/logic** are reported.

---

### Login Page — 🟡 MINOR GAPS
- **Missing password hashing (client-side)**: Legacy hashes password with `sha512(salt + password + salt)` using a server-provided `global_salt` before sending. New code sends plain password (relies on backend/HTTPS).
- **Missing token-based auto-login with nonce challenge**: Legacy uses a sophisticated token system (`ReqNonce` → `sha512(token + nonce)` → `LoginByToken`) with token versioning. New code does simple `localStorage.getItem("token")` → `loginWithToken()`.
- **Missing "카카오톡으로 임시 비밀번호 전송" feature**: Legacy has `sendTempPasswordToKakaoTalk()` accessible via `#oauth_change_pw` button. New code has no equivalent.
- **Missing Kakao OAuth popup flow**: Legacy opens Kakao auth in a popup window (`window.open`), new code does full-page redirect. Minor behavioral difference.
- **Missing running server map iframe**: Legacy has `#running_map` iframe showing current game state map with mobile scaling. New code uses `ServerStatusCard` component (may or may not be equivalent).
- **Missing OTP validity display**: Legacy OTP response shows `validUntil` text ("로그인되었습니다. {validUntil}까지 유효합니다"). New code does not display validity period after OTP success.

### Register Page — 🟡 MINOR GAPS
- **Missing username/nickname duplicate check (async validation)**: Legacy calls `j_check_dup.php` for real-time uniqueness validation on both username and nickname fields. New code has no async duplicate check — only validates on submit.
- **Missing nickname width validation**: Legacy validates `mb_strwidth(value) > 18` (multi-byte string width). New code only checks `min(2)`.
- **Missing password min length discrepancy**: Legacy requires 6+ chars for password. New code requires only 4+.
- **Missing client-side password hashing**: Same as login — legacy hashes with salt before sending.
- **Missing "third_use" (3rd party data consent) checkbox**: Legacy has a separate `third_use` consent field. New code bundles into generic privacy consent.
- **Missing separate terms loading from HTML files**: Legacy loads `terms.1.html` and `terms.2.html` dynamically. New code has hardcoded terms text. (Acceptable difference if content matches.)
- **Missing activation code flow**: Legacy shows "첫 로그인 과정에서 인증 코드를 입력하는 것으로 계정이 활성화됩니다" after registration. New code auto-logs in immediately.

### Account Page — 🟡 MINOR GAPS
- **Missing icon file upload**: Legacy supports file upload for profile icon (`change_icon_form` with `j_icon_change.php`) including file preview. New code only supports URL-based picture change.
- **Missing icon delete**: Legacy has `deleteIcon()` → `j_icon_delete.php`. New code has no icon delete.
- **Missing server-specific icon sync modal**: Legacy has `showAdjustServerModal()` that lets user choose which game servers to apply the icon to (`j_adjust_icon.php`). Entirely absent in new code.
- **Missing "3rd party data consent withdrawal"**: Legacy has `disallowThirdUse()` → `j_disallow_third_use.php` button. New code has no equivalent.
- **Missing OAuth token extension**: Legacy has `extendAuth()` → `oauth_kakao/j_reset_token.php` with date-based availability check. New code has no token extend feature.
- **Missing detailed user info display**: Legacy shows user ID, grade, ACL, join date, third_use status, OAuth type, token validity. New code shows only loginId, displayName, and role.
- **Missing password min length discrepancy**: Legacy requires 6+, new code requires 4+.

### Lobby Page — 🟡 MINOR GAPS
- **Missing per-server detail info**: Legacy fetches `j_server_basic_info.php` for each server showing detailed game info (year/month, scenario, turn term, fiction mode, nation count, user/NPC counts, open time, isUnited status, event status). New code shows less detail (year/month, player count, tick seconds, scenario code).
- **Missing "가오픈" (pre-open) and "reserved" server state**: Legacy distinguishes between open/pre-open/reserved/closed/united/event servers with distinct templates. New code has simpler phase detection.
- **Missing server action buttons per-server**: Legacy shows per-server "장수생성"/"장수빙의"/"장수선택" buttons inline with conditional visibility (`canCreate`, `canSelectNPC`, `canSelectPool`). New code only shows these after selecting a world in the right panel.
- **Missing admin server management in lobby**: Legacy loads `admin_server.ts` plugin inline (server open/close/reset/hard-reset/update/119/notice editing). New code separates admin into `/admin` route.
- **Missing notice display from server**: Legacy shows notice from server data. New code has `notice` state but never populates it from API.
- **Missing `block_general_create` bitfield check**: Legacy checks `game.block_general_create & 1` to conditionally hide "장수생성". New code doesn't check this flag.

### Lobby Join Page — ✅ PARITY (mostly)
- **Minor: Missing "전콘 사용" (use own profile icon) checkbox**: Legacy PageJoin.vue has `args.pic` checkbox to use uploaded icon. New code doesn't have this option.
- **Minor: Missing `blockCustomGeneralName` handling**: Legacy can block custom names (shows "무작위" instead of input). New code always shows name input.
- Overall the stat distribution, personality selection, nation/city selection, crew type, inheritance points, and scout messages are all present.

### Select NPC Page — ✅ PARITY
- Token-based card system, keep/preserve mechanic, timer, refresh, general list view — all present.
- **Minor: Missing "보관(N회)" display**: Legacy shows keep count per card. New code shows simple checkbox without count.
- **Minor: Missing tooltip-based special/personality info**: Legacy renders tooltips for specials and personalities inline. New code shows plain text.

### Select Pool Page — 🟡 MINOR GAPS
- **Missing `validUntil` timer**: Legacy has token expiration timer with visual color change. New code has no expiration handling for pool tokens.
- **Missing `use_own_picture` checkbox**: Legacy has checkbox to use own uploaded icon when building from pool.
- **Missing `validCustomOption` conditional sections**: Legacy conditionally shows picture/ego/stat customization based on `validCustomOption` server config. New code always shows all options.
- **Missing personality (ego) selection during build**: Legacy allows selecting personality via `#selChar` when building. New code's custom build only has name + stats.

### Admin Dashboard — 🟡 MINOR GAPS (vs `admin_server.ts`)
- **Missing per-server open/close buttons**: Legacy has individual open/close buttons per server with ACL checks. New code has world-level lock toggle only.
- **Missing server git update**: Legacy has `serverUpdate()` with git tree-ish input and ACL-based permission (`fullUpdate` vs `update`). Entirely absent in new code.
- **Missing hard reset / install.php link**: Legacy has per-server hard reset (`install_db.php`) and reset (`install.php`) links. New code has simpler world reset via lobby.
- **Missing 서버119 link**: Legacy has per-server "서버119" emergency page link. Not present in new code.
- **Missing error log viewer**: Legacy shows `#showErrorLog` for admins grade >= 5. Not present.
- **Missing ACL-based button enable/disable**: Legacy has fine-grained ACL per server (`openClose`, `update`, `fullUpdate`, `reset`). New code uses simple admin role check.

### Admin Users Page — 🟡 MINOR GAPS (vs `admin_member.ts`)
- **Missing "암호 변경" (reset password) action**: Legacy has `reset_pw` action button. New code has no password reset for users.
- **Missing "유저 차단/해제" with duration**: Legacy has `block` action with day-count prompt and `unblock`. New code has grade-based system but no time-based block.
- **Missing "영구 차단" (ban email)**: Legacy has `banEmailAddress()` to permanently ban by email. New code has no email ban.
- **Missing "allow_join" / "allow_login" global toggles**: Legacy has radio buttons to globally enable/disable registration and login. New code has no equivalent.
- **Missing email display**: Legacy shows user email (with line break at @). New code doesn't show email.
- **Missing per-server general name display**: Legacy shows slot general names per server. New code doesn't.
- **Missing icon display in user list**: Legacy shows user icon (64x64). New code doesn't show icons.
- **Missing `deleteAfter` display**: Legacy shows account deletion schedule date. New code doesn't.

### Admin Members Page — ✅ PARITY (new features exceed legacy)
- New code has bulk actions (일괄 차단/해제/처단), search, and checkbox selection — features not in legacy.
- Legacy `admin_member.ts` equivalent is embedded in `admin_member.ts` user list. The new code's general management is adequate.

### Other Admin Pages (new only, no legacy equivalent)
- `admin/diplomacy/page.tsx` — New feature, no legacy comparison needed
- `admin/game-versions/page.tsx` — New feature
- `admin/logs/page.tsx` — New feature  
- `admin/statistics/page.tsx` — New feature
- `admin/time-control/page.tsx` — New feature

---

## Summary

| Page | Status | Critical Gaps |
|------|--------|---------------|
| Login | 🟡 MINOR | Client-side hashing absent, Kakao temp password missing |
| Register | 🟡 MINOR | No async duplicate check, no activation code flow |
| Account | 🟡 MINOR | No file upload for icon, no server sync, no 3rd party consent withdrawal |
| Lobby | 🟡 MINOR | Less server detail, missing notice population, no block_general_create check |
| Lobby Join | ✅ PARITY | Minor: no 전콘 checkbox, no blockCustomGeneralName |
| Select NPC | ✅ PARITY | Minor: no keep count display |
| Select Pool | 🟡 MINOR | No token timer, no personality selection in build, no validCustomOption |
| Admin Dashboard | 🟡 MINOR | No git update, no per-server open/close, no 서버119 |
| Admin Users | 🟡 MINOR | No password reset, no email ban, no allow_join/login toggles |
| Admin Members | ✅ PARITY | New code exceeds legacy with bulk actions |

**No 🔴 MAJOR GAPS found.** All core flows (login, register, account management, server browsing, general creation, NPC selection, pool selection, admin CRUD) are implemented. Gaps are mostly secondary features and legacy-specific workflows.
