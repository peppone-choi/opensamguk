# 관리 표면 대조표 (API ↔ 화면)

> 상태: 2026-09-24 코드 기준 (휘하 컷오버 B3). 화면은 편의 기능이고 권한은 각 API 가 강제한다(`docs/admin/README.md`).

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

## 엔진 제어 인증 준비 상태

Gateway의 ADMIN 검사 후 `POST /admin/turn-daemon/{pause|resume|catch-up}`는 별도 대상별
control credential과 server/world/generation/revision pin을 붙여 engine에 전달하도록 준비 중이다.
이 절은 로컬 구현·정상 CI 대기 상태를 설명하며 운영 적용이나 검증 완료를 뜻하지 않는다.
관찰용 GET status/health/info의 기존 인증 계약은 유지한다.

Gateway 설정 키 `ENGINE_CONTROL_TARGETS_JSON`은 대상 배열이며 각 record는
`serverId`, `origin`, `worldId`, `generation`, `revision`, `credential` 필드를 갖는다.
Engine 설정 키 `ENGINE_CONTROL_BINDING_JSON`은 `serverId`, `worldId`, `generation`,
`revision`, `credential`을 가진 단일 record다. credential 값은 문서·응답·로그에 기록하지 않는다.
`worldId`는 양의 정수, `generation`은 null이 아닌 음이 아닌 정수이며, `revision`은 1–64자의
영문·숫자·밑줄·하이픈이다. credential 형식은 32byte 난수의 padding 없는 base64url이다.
Gateway는 registry의 canonical origin/generation과 대조하며 서로 다른 대상에 동일 credential을 허용하지 않는다.
사용자 JWT와 기존 internal/deployer token을 control credential로 재사용하지 않는다.
설정 누락/불일치 시 제어 POST는 닫힌다. 실제 process-world와 배포 pin을 대조한 별도 승인·영수증 전에는
생성자 테스트나 설정 구조만으로 운영 준비 완료를 판정하지 않는다.
사용자 인증·ADMIN 권한 거절은 gateway의 기존 401·403을 유지한다. 이를 통과한 뒤 엔진 내부 인증에서
받은 401·403은 gateway 503과 안전한 `ENGINE_CONTROL_UNAVAILABLE` 코드로 표현하며,
사용자 세션 만료로 취급하거나 POST를 자동 재시도하지 않는다. GET/status와 정상 사업 응답 400·409는 유지한다.

## 게임 관리 허브 `/game/admin?tab=…`

옛 번호 경로(`/game/admin1` … `/game/admin8`)와 `/game/tournament-admin`은 404입니다.
공통 운영 기능은 `/game/<서버>/admin?tab=…`에서 사용합니다.

| 탭 | 제거된 옛 경로 | 위험 등급 | API (game-api `/api/admin/**`, 접근 토큰 role=ADMIN) | 패널 |
|---|---|---|---|---|
| 게임 설정 | `admin1` | 가역(턴텀은 엔진 재시작 필요) | `GET /api/admin/game-settings` · `PATCH /api/admin/game-settings` | `GameSettingsPanel` |
| 장수 조치 | `admin2` | 가역·파괴적(삭제) | `GET /api/admin/general-moderation` · `POST /api/admin/general-moderation` | `GeneralModerationPanel` |
| 일제정보 | `admin5` | 조회 | `GET /api/admin/nation-stats?type&type2` (historyStats·sabotageLog 는 원천 부재로 BLOCKED 표시) | `NationStatsPanel` |
| 로그정보 | `admin7` | 조회 | `GET /api/admin/general-log?gen&query_type` | `GeneralLogPanel` |
| 외교정보 | `admin8` | 조회 | `GET /api/admin/diplomacy-all` | `DiplomacyAllPanel` |
| 서버 상태 | (없음) | 가역 · **202 접수 ≠ 반영** | `POST /api/admin/server-status` {OPEN\|PRE_OPEN\|CLOSED} | `ServerStatusPanel` |

## 진입점

- 로비 「계 정 관 리」 → 「관리 (ADMIN만)」 → `/admin`
- 게임 부서 나브 우측 「관리」(ADMIN 계정만 표시) → 현재 서버의 `/game/<id>/admin`
- 상단바(게이트웨이) 「관리」(ADMIN)

## 화면이 만들지 않는 것

- 회원 삭제 복구·감사 로그 조회·서버 롤백 UI — API 가 없다. `docs/admin/operations-and-recovery.md` 절차를 따른다.
- `historyStats`·`sabotageLog`(일제정보) — 원천 부재(BE BLOCKED)라 값을 만들지 않는다.
