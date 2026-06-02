# F0 — Gateway Auth/Lobby Frontend Parity Spec

**Date:** 2026-06-02
**Scope:** `web/gateway/` (Next.js 15.1.3 App Router + React 19). Build the auth + lobby gateway frontend that fronts `app/gateway-api` (:8080).
**Grand truth:** `legacy/devsam-core` PHP `index.php` (login), `i_entrance/entrance.php` (server list / lobby), `hwe/v_join.php` (장수 생성), `_admin*.php` + `i_entrance/j_*_userlist.php` (admin).
**Intentional divergence (locked):** Auth is **LOCAL JWT** — Kakao OAuth is removed. OTP, `global_salt` client-side SHA-512, `sammo_login_token` localStorage auto-login, and all `oauth_kakao/*` flows are **dropped**. Password travels plaintext over TLS to the Next.js proxy, which forwards to gateway-api where BCrypt verifies. Server list is **data-driven** (no hardcoded servers).

This file is the build reference. Korean labels, validation rules, the design-token table, component signatures, and the JWT/cookie contract are preserved verbatim below.

---

## 1. Backend contract (verified against source)

All endpoints on gateway-api (`:8080`). Source: `app/gateway-api/.../controller/AuthController.kt`, `dto/AuthDto.kt`, `service/AuthService.kt`, `config/AdminSeeder.kt`.

### Routes (`@RequestMapping("/auth")`)
| Method | Path | Auth | Request body | Response |
|---|---|---|---|---|
| POST | `/auth/register` | none | `RegisterRequest` | `AuthResponse` (200) |
| POST | `/auth/login` | none | `LoginRequest` | `AuthResponse` (200) |
| POST | `/auth/refresh` | none | `RefreshRequest` | `AuthResponse` (200) |
| GET | `/auth/me` | Bearer | — | `UserResponse` (200) |

Public routes (SecurityConfig): `/auth/**`, `/health`, `/actuator/**`, `OPTIONS /**`. Everything else requires a valid JWT. Session strategy = **STATELESS**, CSRF disabled. Token transport = `Authorization: Bearer <token>`.

### DTOs (exact field names + Bean Validation — these are the validation parity targets)
```kotlin
// RegisterRequest
username: String   @NotBlank @Size(min = 3, max = 50)
password: String   @NotBlank @Size(min = 6, max = 100)
email:    String?  @Email                              // optional, null allowed
nickname: String?  @Size(max = 50)                     // optional, null allowed

// LoginRequest
username: String   @NotBlank
password: String   @NotBlank

// RefreshRequest
refreshToken: String  @NotBlank

// AuthResponse
{ accessToken: String, refreshToken: String, user: UserResponse }

// UserResponse  (== GET /auth/me body)
{ id: Long, username: String, email: String?, nickname: String?, role: String }
```

`web/gateway/lib/types.ts` already mirrors `User` + `AuthResponse` — reuse it; add `RegisterRequest`/`LoginRequest` request shapes.

### Service-layer error strings (verbatim Korean — surface to user)
From `AuthService.kt`, thrown as `IllegalArgumentException`:
| Trigger | Message |
|---|---|
| duplicate username on register | `이미 사용 중인 아이디입니다: {username}` |
| duplicate email on register | `이미 사용 중인 이메일입니다: {email}` |
| user not found (login/refresh/me) | `사용자를 찾을 수 없습니다.` |
| invalid refresh token | `유효하지 않은 리프레시 토큰입니다.` |
| no userId in token | `토큰에서 사용자 ID를 추출할 수 없습니다.` |

Login failure (bad username/password) comes from Spring `AuthenticationManager` → `BadCredentialsException` (not a Korean string). The frontend MUST map a failed login to the legacy verbatim message **`아이디나 비밀번호가 올바르지 않습니다.`** (see §4) since the backend does not provide one.

> **OPEN QUESTION (blocking-ish):** gateway-api has **no `@ControllerAdvice`/`ExceptionHandler`**. `IllegalArgumentException` and `BadCredentialsException` currently surface as Spring's default **HTTP 500 / 401** with a generic body, NOT a clean 400/409 carrying the Korean `reason`. The frontend cannot reliably read the Korean message until either (a) gateway-api adds an exception handler returning `{ "message": "<korean>" }` with 400/409/401, or (b) F0 hardcodes legacy messages keyed off status code. Treat as a foundation prerequisite — see §10.

### Admin
- `role = "ADMIN"` is the admin gate. Spring authority = `ROLE_ADMIN`. Seeded by `AdminSeeder` from env `ADMIN_USERNAME` / `ADMIN_PASSWORD` (idempotent; **peppone** is the intended admin username per project convention). No `userGrade` integers, no ACL arrays in the new system — the legacy `userGrade >= 5/6/7` + `acl` model collapses to a single `role === 'ADMIN'` boolean for F0.
- **No member-management / server-control endpoints exist yet** in gateway-api. The legacy admin surface (`j_set_userlist.php`, `j_server_change_status.php`, `_admin1/2/5.php`) has **no F0 backend**. F0 admin page = scaffold + guard only (role-gated shell with placeholders), real admin APIs are out of F0 scope. See §8 + open questions.

---

## 2. JWT / cookie integration contract (server-side proxy pattern)

**Architecture:** browser → Next.js route handlers (`/api/auth/*`, `/api/proxy/**`) → gateway-api. The browser never calls gateway-api directly and never sees raw tokens (httpOnly). Server-to-server hop has no CORS.

### Token facts (JwtTokenProvider.kt)
- Access token: 15 min (900,000 ms; env `JWT_ACCESS_EXPIRATION`). Claims: `sub`=userId, `username`, `role`, `iat`, `exp`.
- Refresh token: 7 days (604,800,000 ms; env `JWT_REFRESH_EXPIRATION`). Claims: `sub`=userId, `iat`, `exp` (no username/role).
- Algorithm: HS256 (`Keys.hmacShaKeyFor`). Secret = env `JWT_SECRET` (base64).

### Cookie config (set by Next.js route handlers after login/refresh)
```
accessToken  : httpOnly, secure(prod), sameSite='strict', maxAge=900000,     path='/'
refreshToken : httpOnly, secure(prod), sameSite='strict', maxAge=604800000,  path='/api/auth/refresh'
```
`secure = process.env.NODE_ENV === 'production'`. Refresh cookie path is scoped to `/api/auth/refresh` so it is only sent to the refresh endpoint.

### Route handlers (all under `web/gateway/app/api/`)
- `POST /api/auth/login` → forward body to `${GATEWAY_API_URL}/auth/login`; on success set both cookies, return `AuthResponse` (or strip tokens and return only `user` — see open question on token echo).
- `POST /api/auth/register` → forward to `/auth/register`; on success set cookies, return body.
- `POST /api/auth/refresh` → read `refreshToken` cookie, forward `{refreshToken}` to `/auth/refresh`; rotate both cookies. 401 if no cookie.
- `GET /api/auth/me` → read `accessToken` cookie, call `/auth/me` with `Authorization: Bearer`; 401 passthrough on expiry.
- `POST /api/auth/logout` → clear both cookies (set maxAge 0), return `{ok:true}`. (Legacy `j_logout.php` → redirect `../`.)
- `GET|POST /api/proxy/[...path]` → attach `Authorization: Bearer <accessToken cookie>` and forward to gateway-api; 401 if no cookie. (Used by lobby/admin for future authenticated reads.)

### Middleware (`web/gateway/middleware.ts`)
- Public routes (no token needed): `/`, `/login`, `/join`.
- Auth API routes always pass: `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`.
- Protected routes (`/lobby`, `/admin`, other `/api/proxy/**`): if no `accessToken` cookie → redirect to `/login` (pages) or 401 (api).
- `/admin` additionally requires `role === 'ADMIN'` — middleware can only check cookie presence, so the **role check is enforced in the `/admin` page via `/api/auth/me`** (middleware cannot decode httpOnly JWT without the secret; do NOT put the secret in middleware). Redirect non-admins to `/lobby`.
- Matcher: `['/((?!_next/static|_next/image|favicon.ico).*)']`.

### Env
```
# web/gateway/.env.local (dev)
GATEWAY_API_URL=http://localhost:8080          # server-side only, NOT NEXT_PUBLIC
NODE_ENV=development
# prod
GATEWAY_API_URL=http://gateway-api-internal:8080
```
`lib/server-api.ts` already exports `GATEWAY_API_URL`. Do **not** expose it as `NEXT_PUBLIC_*`.

---

## 3. LOGIN screen (`/login`)  — legacy `index.php`

### Layout (render order)
1. Navbar brand: **`삼국지 모의전투 HiDCHe`** (also the page `<title>`).
2. Login card. Card header: **`로그인`**.
3. Optional map/현황 preview region — **drop for F0** (legacy `running_map` iframe; no recent-map endpoint in the new stack).
4. Footer links: **`개인정보처리방침`** · **`이용약관`** (can be inert placeholders for F0).

### Form fields (verbatim labels + types)
| Field | id | type | Label | autocomplete | placeholder | required |
|---|---|---|---|---|---|---|
| username | `username` | text | `계정명` | username | `계정명` | yes |
| password | `password` | password | `비밀번호` | current-password | `비밀번호` | yes |

Primary button: **`로그인`**. Dropdown `비밀번호 초기화` (password reset) — **drop for F0** (Kakao-only flow). `global_salt` hidden field, client SHA-512, and the OTP modal (`인증 코드 필요` / `인증 코드` / `취소` / `제출`) are **dropped** (LOCAL-JWT divergence).

### Client validation (verbatim messages, ported from `login.ts`)
- username empty → **`유저명을 입력해주세요`**
- password empty → **`비밀번호를 입력해주세요`**

### Flow
Submit → `POST /api/auth/login {username,password}` (`credentials:'include'`) → on 200 cookies set, redirect to **`/lobby`**. On failure show **`아이디나 비밀번호가 올바르지 않습니다.`** (login disabled, if ever surfaced: **`현재는 로그인이 금지되어있습니다!`**). Link to `/join` for new accounts.

---

## 4. JOIN — TWO distinct legacy screens; F0 builds the ACCOUNT one

> The legacy split: **member account creation** (`oauth_kakao/join.php`, account `username/password/nickname/email`) vs **장수 생성 / character creation** (`hwe/v_join.php` → `General/Join` API, stats/nation/inheritance). The `read:join` report documents the **character-creation** screen in full. **For F0 scope (gateway auth/lobby) the `/join` page = ACCOUNT REGISTRATION** mapping 1:1 to `POST /auth/register`. The character-creation 장수 생성 screen belongs to the **game** frontend (`web/game`, per-server) and is a P7 game-page task, NOT F0.** The full character-creation rules are preserved in §4b below as the future build reference, but `/join` in `web/gateway` implements only account registration.

### 4a. Account registration `/join` (F0 — maps to `RegisterRequest`)
Fields (derived from `RegisterRequest` validation + legacy join.ts account descriptor):
| Field | type | Label | rule | empty/invalid message (verbatim) |
|---|---|---|---|---|
| username | text | `계정명` | required, 3–50 (`@Size(min=3,max=50)`); legacy client min 4 | `유저명을 입력해주세요` / `{L}글자 이상 입력하셔야 합니다` / `{L}자를 넘을 수 없습니다` |
| password | password | `비밀번호` | required, 6–100 (`@Size(min=6,max=100)`); legacy client min 6 | `비밀번호를 입력해주세요` / `비밀번호는 적어도 {L}글자 이상이어야 합니다` |
| confirm_password | password | (확인) | must equal password | `비밀번호가 일치하지 않습니다` |
| nickname | text | (별명) | optional, max 50; legacy 글자 너비 ≤ 18 | `글자 너비가 알파벳 18자보다 길지 않아야합니다` |
| email | email | (이메일) | optional, `@Email` format | (format error) |

> Reconcile min-length: backend username min = **3**; legacy client min = **4**. Backend is grand truth for the new stack → **use 3** (client validation min 3, max 50). Password min **6** (matches both). nickname: backend max **50** (legacy width-18 dropped; nickname is a plain `@Size(max=50)` string in the new entity).

Agreement checkboxes (legacy): `동의해야만 가입하실 수 있습니다.` — F0 may render `개인정보처리방침`/`이용약관` agree checkboxes as UI but they are not enforced by the backend (optional polish).

Server duplicate errors (verbatim, from AuthService): `이미 사용 중인 아이디입니다: {username}`, `이미 사용 중인 이메일입니다: {email}`.

Flow: submit → `POST /api/auth/register` → on 200 cookies set → success toast then redirect to `/lobby`. Legacy success copy (account): **`회원 등록되었습니다.\n첫 로그인 과정에서 인증 코드를 입력하는 것으로 계정이 활성화됩니다.`** — the OTP-activation half is dropped; use **`회원 등록되었습니다.`** for F0.

### 4b. Character creation 장수 생성 (FUTURE — web/game P7, preserved here verbatim)
Page title `장수 생성`. Endpoint legacy `api.php?path=General/Join` (form-urlencoded). NOT built in F0. Preserved rules:

**Stat caps (GameConst):** `defaultStatMin=50`, `defaultStatMax=50`, `defaultStatTotal=150`, `bornMinStatBonus=1`, `bornMaxStatBonus=5`.

**Fields:** nation (dropdown, `국가명`+`임관권유문`), 장수명 (text, width 1–18; `blockCustomGeneralName` → "무작위"), 전콘 사용 (checkbox), 성격 (select, default `Random`), 능력치 통솔/무력/지력 (number each 50–50), 능력치 조절 buttons (`랜덤형`/`통솔무력형`/`통솔지력형`/`무력지력형`), 유산 포인트 (보유한 유산 포인트 / 필요 유산 포인트), 천재로 생성 (select, `사용안함`), 도시 (select, `사용안함`), 턴 시간 지정 (select 0–59), 추가 능력치 고정(통/무/지) (3 numbers, sum 3–5), actions `장수 생성` / `다시 입력`.

**Verbatim validation/error messages (server, Join.php):**
```
잘못된 접근입니다!!!
장수 직접 생성이 불가능한 모드입니다.
이미 등록하셨습니다!
이미 있는 장수입니다. 다른 이름으로 등록해 주세요!
더이상 등록할 수 없습니다!
이름이 짧습니다. 다시 가입해주세요!
이름이 유효하지 않습니다. 다시 가입해주세요!
능력치가 {N}을 넘어섰습니다. 다시 가입해주세요!
보너스 능력치가 잘못 지정되었습니다. 다시 가입해주세요!
보너스 능력치가 음수입니다. 다시 가입해주세요!
보너스 능력치 합이 잘못 지정되었습니다. 다시 가입해주세요!
유산 포인트가 부족합니다. 다시 가입해주세요!
이미 천재가 모두 나타났습니다. 다시 가입해주세요!
도시가 잘못 지정되었습니다. 다시 가입해주세요!
```
Client confirm/info: `설정한 능력치가 {N}으로, 실제 최대치인 {N}보다 적습니다.\r\n그래도 진행할까요?`, success `정상적으로 생성되었습니다. \n위키와 팁/강좌 게시판을 꼭 읽어보세요!`. UI info: `모든 능력치는 ( {MIN} <= 능력치 <= {MAX} ) 사이로 잡으셔야 합니다.`, `능력치의 총합은 {SUM} 입니다. 가입후 {BONUSMIN} ~ {BONUSMAX} 의 능력치 보너스를 받게 됩니다.`, `임의의 도시에서 재야로 시작하며 건국과 임관은 게임 내에서 실행합니다.`. `block_general_create & 1` = 생성 금지, `& 2` = 무작위 이름.

---

## 5. ENTRANCE / LOBBY (`/lobby`) — legacy `i_entrance/entrance.php`

The post-login server-selection screen. Server list is **data-driven** (config/DB → API), never hardcoded.

### Layout (render order)
1. Navbar — brand **`삼국지 모의전투 HiDCHe`**, menu from config.
2. Server notice (`server_notice`) — orange system notice text (legacy `system.NOTICE`).
3. Server list table (`server_list_table`). Caption **`서 버 선 택`**. Columns: **`서 버`** / **`정 보`** / **`캐 릭 터`** (2-col span) / **`선 택`**.
4. Account management (`user_info`). Title **`계 정 관 리`**. Buttons: `비밀번호 & 전콘 & 탈퇴` (legacy user_info — F0 may stub or drop), **`로 그 아 웃`** (→ `POST /api/auth/logout` → redirect `/`).
5. Admin panel link — show only if `me.role === 'ADMIN'` (→ `/admin`).

### Server-row states + verbatim labels
- Closed server: **`- 폐 쇄 중 -`**.
- Status badges: **`§이벤트 종료§`** (isUnited=3) / **`§이벤트 진행중§`** (=1) / **`§천하통일§`** (=2) / **`-가오픈 중-`** (pre-open) / **`<{N}국 경쟁중>`** (competition).
- Timeline (running): `서기 <%year%>년 <%month%>월 (<%scenario%>)`, `유저 : <%userCnt%> / <%maxUserCnt%>명 NPC : <%npcCnt%>명 (<%turnTerm%>분 턴 서버)`, `(상성 설정:<%fictionMode%>), (기타 설정:<%otherTextInfo%>)`.
- Reserved: `- 오픈 일시 : <%opentime%> -`, `- 가오픈 일시 : <%openDatetime%> -`.
- Character cell: has char → `<%name%>` + **`입장`** button (→ game server root `/{serverPath}/`); capacity full → **`- 장수 등록 마감 -`**; no char → **`- 미 등 록 -`** + actions **`장수생성`** / **`장수빙의`** / **`장수선택`** (conditional on canCreate / npcMode `가능` / `선택 생성`).
- Footnotes (verbatim): `★ 1명이 2개 이상의 계정을 사용하거나 타 유저의 턴을 대신 입력하는 것이 적발될 경우 차단 될 수 있습니다.` / `계정은 한번 등록으로 계속 사용합니다. 각 서버 리셋시 캐릭터만 새로 생성하면 됩니다.`

### Server inventory (data-driven mechanism)
Legacy: `ServConfig::getServerList()` → `j_server_get_status.php` (list) + per-server `j_server_basic_info.php` (detail). For the new stack:
- Server entry: `{ name, korName, color, exists, enable }`. Detail: `{ reserved?, game?, me? }` (see `read:entrance-lobby` §3 for full shapes).
- Color map (reference): 체=#FFFFFF, 퀘=yellow, 풰=orange, 퉤=magenta, 냐=#e67e22, 퍄=#9b59b6, 훼=red.

> **OPEN QUESTION:** No server-list endpoint exists in gateway-api. F0 options: (a) serve a static `config/servers.json` from the Next.js side and render rows from it (zero backend, fastest to ship the "lobby" gate); (b) add a `GET /servers` gateway-api endpoint. The F0 acceptance ("로그인→로비→게임입장") only requires that the lobby renders at least one entry with an `입장` link to the game server root — so **F0 ships option (a): a config-driven server list (`config/servers.json`) rendering the legacy table + 입장**, deferring per-server live `game`/`me` detail to P7. The `입장` link target is the game frontend root (e.g. `${NEXT_PUBLIC_GAME_URL}/game` or `/{serverName}/`).

---

## 6. ADMIN (`/admin`) — legacy `_admin*.php` + `i_entrance/j_*`

Legacy capabilities (member mgmt, server open/close, game-env tuning, character sanctions) span many endpoints gated by `userGrade >= 5/6/7` + ACL. **None of these backends exist in the new gateway-api.** F0 collapses the permission model to `role === 'ADMIN'`.

**F0 admin scope = guard + shell only:** a role-gated `/admin` page that (1) calls `/api/auth/me`, (2) redirects non-`ADMIN` to `/lobby`, (3) renders an admin shell with placeholder sections (member management, server control, game env) marked "준비 중". Real admin APIs (member ban/block/level, server open/close, game-env) are **out of F0 scope** (backlog). Preserve the legacy capability inventory (`read:admin`) as the future build reference.

Verbatim legacy admin labels worth preserving for later: member actions `강제탈퇴`/`암호변경`/`유저차단`/`차단해제`/`영구차단`/`별도권한`; server actions `폐쇄`/`오픈`/`리셋`/`하드리셋`/`서버119`/`업데이트`; env `운영자메세지`/`중원정세추가`/`시작시간변경`/`최대 장수`/`최대 국가`/`시작 년도`/`턴시간` + turn presets `1분턴`…`120분턴`.

---

## 7. Design system (tokens + reusable components)

Copy `web/game/app/globals.css` verbatim into `web/gateway/app/globals.css` (it is game-agnostic). Theme = dark war-room.

### Design tokens (verbatim from globals.css `:root`)
| Category | Token | Value |
|---|---|---|
| bg | `--bg-base` | `#0a0a0a` |
| bg | `--bg-elevated` | `#141414` |
| bg | `--bg-card` | `#1a1a1a` |
| bg | `--bg-hover` | `#222222` |
| bg | `--bg-active` | `#2a2a2a` |
| accent | `--gold` | `#c9a227` |
| accent | `--gold-dim` | `#8a7020` |
| accent | `--crimson` | `#c62828` |
| accent | `--crimson-dim` | `#8b1a1a` |
| accent | `--jade` | `#2e7d32` |
| accent | `--jade-dim` | `#1b5e20` |
| text | `--text-primary` | `#f0f0f0` |
| text | `--text-secondary` | `#a0a0a0` |
| text | `--text-muted` | `#666666` |
| border | `--border-subtle` | `#333333` |
| border | `--border-medium` | `#444444` |
| border | `--border-accent` | `var(--gold)` |
| font | `--font-sans` | `'Pretendard', -apple-system, BlinkMacSystemFont, sans-serif` |
| font | `--font-mono` | `'JetBrains Mono', 'Fira Code', monospace` |
| size | `--text-xs … --text-2xl` | `0.75 / 0.875 / 1 / 1.125 / 1.25 / 1.5 rem` |
| space | `--space-xs … --space-xl` | `0.25 / 0.5 / 1 / 1.5 / 2 rem` |
| radius | `--radius-sm` / `--radius-md` | `4px` / `8px` |
| shadow | `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.3)` |
| shadow | `--shadow-md` | `0 4px 12px rgba(0,0,0,0.4)` |
| shadow | `--shadow-gold` | `0 0 12px rgba(201,162,39,0.2)` |
| motion | `--transition-fast` / `--transition-base` | `150ms ease` / `250ms ease` |

Breakpoints: tablet `max-width:1023px` (hide sidebar, show bottom-nav), mobile `max-width:767px`, `prefers-reduced-motion: reduce` kills animations. Animations: `fadeInUp`, `slideInRight`, `goldPulse`.

> **LATENT BUG (carry over with care):** globals.css references `var(--text-inverse)` on gold buttons (`.game-header-cmd`, `.cmd-cats button.active`, `.cmd-submit`, `.error-state button`) but `--text-inverse` is **not defined** in `:root` → gold-button text currently has no color. When copying to gateway, **add `--text-inverse: #0a0a0a;`** (dark text on gold) so primary buttons are legible. Verify against game before/after to avoid divergence (or fix in both).

### Component signatures (from `web/game/components/*` — reusable as-is unless noted)
| Component | File (game) | Props | Reuse for gateway |
|---|---|---|---|
| `GameCard` | `components/GameCard.tsx` | `{children, className?, style?}` | as-is |
| `GameTable` | `components/GameTable.tsx` | `{headers: string[], rows: (string\|number\|ReactNode)[][]}` | as-is (server list) |
| `StatusBadge` | `components/StatusBadge.tsx` | `{variant:'gold'\|'crimson'\|'jade'\|'muted', children}` | as-is (server status §이벤트…) |
| `Toast` | `components/Toast.tsx` | `{toasts: ToastItem[], onRemove:(id)=>void}` | as-is |
| `ErrorBoundary` | `components/ErrorBoundary.tsx` | `{children}` | as-is |
| `Shell` | `components/Shell.tsx` | `{children}` | clone, drop game `useSSE`/`CommandModal`, swap nav |
| `Header` | `components/Header.tsx` | `{onCommand:()=>void}` | clone, replace turn/resource with brand + user/logout |
| `Sidebar` | `components/Sidebar.tsx` | none (uses `usePathname`) | clone, swap NAV_ITEMS (로비/관리/로그아웃) |
| `BottomNav` | `components/BottomNav.tsx` | `{onCommand}` | clone, swap nav |

`useToast` hook (`web/game/hooks/useToast.ts`) is reusable. `useSSE` is game-only — drop. F0 adds gateway-specific: `lib/auth.ts` (cookie read helpers + me()), `lib/api.ts` (client fetch wrapper hitting `/api/*`), `lib/constants.ts` (gateway nav + labels).

### Pretendard
globals.css names `'Pretendard'` but it is not loaded. In `web/gateway/app/layout.tsx` add a Pretendard webfont (`next/font` local or the Pretendard CDN `@import`) and set `<html lang="ko">` (already correct) + apply font to `<body>`. Match whatever the game uses; if game also lacks it, this is a shared polish item.

---

## 8. Page / route map (App Router, under `web/gateway/app/`)
| Route | Page file | Public? | Notes |
|---|---|---|---|
| `/` | `app/page.tsx` | yes | Entrance/landing → brand + 로그인/회원가입 CTAs; redirect to `/lobby` if `accessToken` cookie present |
| `/login` | `app/login/page.tsx` | yes | §3 |
| `/join` | `app/join/page.tsx` | yes | §4a account register |
| `/lobby` | `app/lobby/page.tsx` | protected | §5 server list + 입장 + logout + admin link |
| `/admin` | `app/admin/page.tsx` | protected + ADMIN | §6 guard + shell |

API route handlers: `app/api/auth/login`, `app/api/auth/register`, `app/api/auth/refresh`, `app/api/auth/me`, `app/api/auth/logout`, `app/api/proxy/[...path]` (all `route.ts`). Middleware: `middleware.ts`.

---

## 9. F0 acceptance gate
**실제 로그인 → 로비 → 게임 입장 동작**: a user registers (or the seeded `peppone` admin logs in) at `/login`, lands on `/lobby` rendering the server table (data-driven), and clicks **`입장`** to reach the game server root. httpOnly cookies are set, `/api/auth/me` resolves the user, middleware gates `/lobby` and `/admin`, and logout clears cookies and returns to `/`.

---

## 10. Open questions / risks
1. **gateway-api has no exception handler** → Korean error `reason` strings are not delivered with a clean status. Decide: add `@ControllerAdvice` to gateway-api (recommended, small) returning `{message}` + 400/409/401, OR have F0 map by status code to legacy verbatim strings. Affects login/register error UX parity.
2. **No server-list backend** → F0 ships a config-driven `config/servers.json` to satisfy the lobby gate; confirm the `입장` target URL (game frontend root vs `/{serverName}/`) and whether per-server live `game`/`me` detail is in F0 or deferred to P7.
3. **`입장` destination:** what is the game-server root URL from the gateway? (`web/game` runs on :3001; need `NEXT_PUBLIC_GAME_URL` or path convention.)
4. **Login token echo:** should `/api/auth/login` return the full `AuthResponse` (with tokens) to the browser, or strip tokens and return only `user` (tokens live only in httpOnly cookies)? Recommend stripping tokens client-side for XSS hygiene.
5. **Admin scope:** confirm F0 admin = guard+shell only (no real member/server APIs). The full legacy admin surface needs new gateway-api endpoints — backlog as a separate phase.
6. **`--text-inverse` undefined** in shared globals.css (gold buttons). Fix in gateway copy and reconcile with game.
7. **Char-creation 장수 생성** (`read:join` §4b) is a **game** (web/game) P7 page, not F0 — confirm this boundary.
8. **Dropped legacy features** (confirm intentional): OTP modal, Kakao buttons, `global_salt`/client SHA-512, `sammo_login_token` localStorage auto-login, password-reset dropdown, login-page map preview, `user_info.php` (`비밀번호 & 전콘 & 탈퇴`).
