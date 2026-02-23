# Frontend Auth + Lobby Parity Report

**Date:** 2026-02-23  
**Status:** Implemented  
**TypeScript compilation:** ✅ Clean (0 errors)

## Reference Sources

| Area | Legacy | Core2026 |
|------|--------|----------|
| Auth | `legacy/hwe/index.php` | `core2026/app/gateway-frontend/src/views/HomeView.vue` |
| Lobby | `legacy/hwe/index.php` (v_front Vue) | `core2026/app/gateway-frontend/src/views/LobbyView.vue` |
| Join | `legacy/hwe/v_join.php` | `core2026/app/game-frontend/src/views/JoinView.vue` |
| NPC Select | `legacy/hwe/select_npc.php` | (same JoinView possess tab) |

## Changes Made

### 1. Login Page (`/login`)

| Feature | Before | After |
|---------|--------|-------|
| Auto-redirect if authenticated | ❌ | ✅ Checks `isInitialized && isAuthenticated`, redirects to `/lobby` |
| Title text | "오픈삼국 로그인" | "삼국지 모의전투 HiDCHe" (legacy parity) |
| Quick register button ("가입 & 로그인") | ❌ | ✅ Combined register flow on login page, expands to show displayName field |
| Field labels | "아이디" / "비밀번호" | "계정명" / "비밀번호" (legacy parity) |

### 2. Register Page (`/register`)

No changes needed — the separate registration page is adequate. The login page now also offers inline registration via "가입 & 로그인" for legacy parity.

### 3. Account Page (`/account`)

| Feature | Before | After |
|---------|--------|-------|
| Profile picture (전콘) management | ❌ | ✅ URL input + change button, preview display |
| Password change | ✅ | ✅ |
| Account deletion | ✅ | ✅ |
| Vacation toggle | ✅ | ✅ |
| OAuth section | ✅ (placeholder) | ✅ |

### 4. Lobby Page (`/lobby`)

| Feature | Before | After |
|---------|--------|-------|
| Notice display (top) | ❌ | ✅ Orange bold notice banner (matches core2026 LobbyView) |
| Multi-account warning | ❌ | ✅ Red warning text about dual accounts/proxy play |
| Account management section | ❌ | ✅ "비밀번호 & 전콘 & 탈퇴", "로그아웃", admin link buttons |
| Persistent registration note | ❌ | ✅ Note about account persistence across resets |

### 5. Join Page (`/lobby/join`)

| Feature | Before | After |
|---------|--------|-------|
| Personality/character selection | ❌ | ✅ Dropdown with 8 personality types + info text |
| Stat presets (랜덤형/통솔형/무력형/지력형/균형형) | ❌ | ✅ 5 preset buttons matching legacy/core2026 |
| Nation recruitment messages (임관 권유) | ❌ | ✅ Card showing nations with scout messages |
| Inheritance points (유산 포인트) | ❌ | ✅ Special selection, city selection, bonus stats (0 or 3-5) |
| "다시 입력" reset button | ❌ | ✅ Resets to balanced preset |

### 6. Select NPC Page (`/lobby/select-npc`)

| Feature | Before | After |
|---------|--------|-------|
| NPC card system | ✅ | ✅ |
| Valid until timer | ✅ | ✅ |
| Keep/preserve system | ✅ | ✅ |
| Full general list table ("장수 목록 보기") | ❌ | ✅ Table with face/name/age/nation/level/stats/exp/NPC status, paginated |
| "장수 더 보기" pagination | ❌ | ✅ Load more button |

### 7. Select Pool Page (`/lobby/select-pool`)

No changes — this is a new feature not present in legacy. Already functional.

## Architectural Notes

- **Stat system:** Legacy uses 3 stats (통솔/무력/지력), current uses 5 stats (+ 정치/매력). Kept as-is since the backend defines this.
- **Multi-server vs multi-world:** Legacy/core2026 have multi-server profiles; current has single gateway with multiple worlds. Lobby already handles this correctly.
- **Legacy gateway TS files:** Path `legacy/src/ts/gateway/` and `legacy/src/ts/v_join.ts` do not exist in the repository. Analysis was based on PHP templates and core2026 Vue sources.

## Remaining Gaps (Low Priority)

- Inheritance point special list is empty (needs backend to provide available specials via API)
- Turn time zone selection from core2026 JoinView (not applicable in current architecture)
- Map preview per world in lobby (ServerStatusCard already shows aggregate map)
- Special/character info tooltips from `select_npc.php` inline JS (specialInfo/characterInfo maps)
