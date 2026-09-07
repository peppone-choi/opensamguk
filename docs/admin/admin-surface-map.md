# 관리 표면 대조표 (API ↔ 화면)

> 상태: 2026-09-06 코드 기준 (ADR-LITE-049 Phase 3). 화면은 편의 기능이고 권한은 각 API 가 강제한다(`docs/admin/README.md`).

새 기능을 지어내지 않는다 — 아래 표의 API 는 전부 `main` 에 이미 있는 것이고, 화면은 그것을 빠짐없이 노출하는 것이 목표다.

## Gateway 운영 콘솔 `/admin`

| 섹션 | 위험 등급 | API (gateway-api `AdminController`, `/admin/**` = ROLE_ADMIN) | 화면 컴포넌트 |
|---|---|---|---|
| 개요 | 조회 | `GET /admin/version` · `GET /admin/deploy/status?serverId` · `GET /admin/turn-daemon/status?serverId` | `components/admin/AdminOverview.tsx` |
| 회원 관리 | 가역·파괴적 | `GET /admin/users` · `POST /admin/users/{id}/{action}` · `POST /admin/users/scrub/{deleted\|old}` · `POST /admin/ban-email` · `POST /admin/system/{allow_login\|allow_join}` | `components/admin/MemberControl.tsx` |
| 게시판 관리 | 가역 | board-api `PATCH /board/posts/{id}/pin` · `DELETE /board/posts/{id}` · `DELETE /board/posts/{id}/comments/{cid}` (`/api/board/**` 프록시) | `components/admin/BoardControl*.tsx` |
| 서버 제어 | 배포·파괴적 | `POST /admin/servers` · `DELETE /admin/servers/{id}` · `POST /admin/servers/{id}/reset` · `GET /admin/servers/operations/{operationId}` · `GET /admin/scenarios` · `POST /admin/deploy` · `GET /admin/deploy/status` | `app/admin/page.tsx` `ServerControl`(생성·버전 표·서버별 리셋/삭제/재배포, `lib/admin-server-lifecycle.ts` 폴링) |
| 게임 환경 | 가역 | `POST /admin/turn-daemon/{pause\|resume}` · `GET/PATCH /admin/env/shared` · `GET/PATCH /admin/env/servers/{id}` · game-api `PATCH /api/admin/game-settings`(운영자 메시지·턴텀) | `app/admin/page.tsx` `GameEnvControl` |
| 공지 | 가역 | `GET /notices`(공개) · `GET/POST /admin/notices` · `PUT /admin/notices/{id}` · `PATCH /admin/notices/{id}/pin` · `DELETE /admin/notices/{id}`(soft) | `components/admin/NoticeControl.tsx` |

## 게임 관리 허브 `/game/admin?tab=…`

옛 경로(`/game/admin1` … `/game/tournament-admin`)는 그대로 열리며 같은 패널을 그린다.

| 탭 | 옛 경로 | 위험 등급 | API (game-api `/api/admin/**`, 접근 토큰 role=ADMIN) | 패널 |
|---|---|---|---|---|
| 게임 설정 | `admin1` | 가역(턴텀은 엔진 재시작 필요) | `GET /api/admin/game-settings` · `PATCH /api/admin/game-settings` | `GameSettingsPanel` |
| 장수 조치 | `admin2` | 가역·파괴적(삭제) | `GET /api/admin/general-moderation` · `POST /api/admin/general-moderation` | `GeneralModerationPanel` |
| 일제정보 | `admin5` | 조회 | `GET /api/admin/nation-stats?type&type2` (historyStats·sabotageLog 는 원천 부재로 BLOCKED 표시) | `NationStatsPanel` |
| 로그정보 | `admin7` | 조회 | `GET /api/admin/general-log?gen&query_type` | `GeneralLogPanel` |
| 외교정보 | `admin8` | 조회 | `GET /api/admin/diplomacy-all` | `DiplomacyAllPanel` |
| 토너먼트 관리 | `tournament-admin` | 가역 | `TournamentController` 관리 경로 | `TournamentAdminPanel` |
| 서버 상태 | (신규 화면) | 가역 · **202 접수 ≠ 반영** | `POST /api/admin/server-status` {OPEN\|PRE_OPEN\|CLOSED} — Phase 3 이전까지 FE 미배선 | `ServerStatusPanel` |

## 진입점

- 로비 「계 정 관 리」 → 「관리 (ADMIN만)」 → `/admin`
- 게임 부서 나브 우측 「관리」(ADMIN 계정만 표시) → 현재 서버의 `/game/<id>/admin`
- 상단바(게이트웨이) 「관리」(ADMIN)

## 화면이 만들지 않는 것

- 회원 삭제 복구·감사 로그 조회·서버 롤백 UI — API 가 없다. `docs/admin/operations-and-recovery.md` 절차를 따른다.
- `historyStats`·`sabotageLog`(일제정보) — 원천 부재(BE BLOCKED)라 값을 만들지 않는다.
