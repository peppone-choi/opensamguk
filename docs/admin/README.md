# 오픈삼국 관리자 매뉴얼

> 상태: 현재 Gateway·게임 관리자 화면 기준
> 마지막 검토: 2026-08-29

관리 표면은 두 곳입니다.

| 표면 | 대표 경로 | 책임 |
|---|---|---|
| Gateway 관리자 | `/admin` (운영 콘솔: 개요 · 회원 관리 · 게시판 관리 · 서버 제어 · 게임 환경 · 공지) | 회원, 게시판, 서버 생성·리셋·삭제, 버전, 공용·서버 환경, 공지 |
| 게임 관리자 | `/game/admin`(허브, 탭 `?tab=settings\|generals\|stats\|logs\|diplomacy\|tournament\|status`) — 옛 `/game/admin1`, `admin2`, `admin5`, `admin7`, `admin8`, `tournament-admin` 도 그대로 열림 | 현재 월드 설정·장수 조치·통계·로그·외교·토너먼트·서버 상태 |

브라우저에서 탭이 보이는 것은 편의 기능입니다. 실제 권한은 Gateway의 `ROLE_ADMIN`, game-api의 인증 주체,
장수 소유권·직책과 각 API의 서버 검사가 결정합니다.

## 먼저 읽을 문서

- [관리 표면 대조표](./admin-surface-map.md): 어느 화면이 어느 API 를 부르는지, 위험 등급

- [서버 생명주기](./server-lifecycle.md): 생성, 시드, 리셋, 삭제, 버전 승격
- [회원·게임 관리](./member-and-game-management.md): 회원, 장수, 게시판, 게임 설정과 읽기 화면
- [운영·복구](./operations-and-recovery.md): 승인, 백업, 헬스, 배포 전후, 장애와 롤백

## 위험 등급

| 등급 | 예 | 기본 원칙 |
|---|---|---|
| 조회 | 버전, 통계, 로그, 외교 | 대상 서버와 갱신 시각 확인 |
| 가역 변경 | 로그인/가입 허용, 일부 게임 설정, daemon pause/resume | 변경 전 값을 기록하고 한 항목씩 적용 |
| 배포 | 서버별 이미지 태그 변경 | immutable tag, migration, health와 rollback 확인 |
| 파괴적 변경 | 서버 reset/delete, DB·Redis volume 초기화 | 명시 승인, 백업·복구 계획, 대상 ID 재확인 없이는 실행 금지 |

관리 화면의 confirm은 최종 안전 경계가 아닙니다. 운영자는 대상 서버, 현재 기수, 데이터 영향과 승인 기록을
별도로 확인해야 합니다.

## 서버 생명주기 operation 판정

> 배포 상태: 아래는 현재 개발 branch에 구현된 rollout 계약입니다. production 배포·실서버
> 검증은 아직 이 문서로 증명되지 않았으며, 배포 증거 없이 현재 운영 기능으로 간주하지 않습니다.

서버 생성·리셋·삭제의 첫 응답은 작업을 내구적으로 **접수**했다는 뜻일 수 있지만, Docker 변경·런타임
검증·Gateway registry 반영이 **완료**됐다는 뜻은 아닙니다. UI와 control workflow는 반드시 같은
`operationId`를 조회해 `succeeded`를 확인한 뒤에만 완료로 표시합니다.

| 상태 | 운영 판정 |
|---|---|
| `pending`, `running` | 접수/실행 중. 완료·실패 둘 다 아님 |
| `recovery_required` | 비종료. 영속 journal을 기준으로 repair가 필요하며 일반 mutation은 fail-closed |
| `succeeded` | 후속 검증과 registry 갱신을 진행해도 되는 유일한 성공 상태 |
| `failed`, `cancelled` | 종료 실패. 제한된 `publicMessage`로 종료하고 성공처럼 registry를 새로 고치지 않음 |

`operationId`는 확인 1회당 하나의 32자리 소문자 hex로 생성하고, 모든 재시도와 polling에서 같은 값을
재사용합니다. 장애 에스컬레이션에는 이 ID와 서버 ID, 발생 시각만 우선 적고, HTTP 응답 본문·토큰·
`.env`는 첨부하지 않습니다.

Polling deadline이 끝나도 기저 operation이 성공했거나 실패했다는 뜻이 아닙니다. 화면은 "아직 진행
중"과 `operationId`를 보여주고, 운영자는 같은 ID로 상태를 다시 조회합니다. 새 ID로 파괴적 요청을
즉시 반복하지 않습니다.

`recovery_required`인 경우에는 점검 marker를 닫힌 채 두고, deployer loopback의
`POST /maintenance/repair`를 승인된 절차로 실행합니다. Repair가 런타임·데이터·공유 registry 후조건을
다시 검증하고 같은 operation을 `succeeded`로 영속한 뒤에만 journal과 점검 장벽을 정리합니다.
Repair가 실패하거나 `recovery_required`가 유지되면 장벽을 임의로 열지 않고 에스컬레이션합니다.

## 현재 운영 제어면

프로덕션 정본은 별도 [`opensamguk-docker`](https://github.com/peppone-choi/opensamguk-docker) 저장소의
shared/server/deployer 모델입니다. 이 앱 저장소의 `docker-compose.production.yml`과 `scripts/deploy.sh`는 호환
표면이며 멀티서버 운영 정본이 아닙니다.

## 아직 완료로 보지 않는 기능

- 공지 고정·soft-delete를 포함한 게시판 관리자 기능: [#223](https://github.com/peppone-choi/opensamguk/issues/223)
- 서버 레지스트리 DB화와 무재시작 반영: [#466](https://github.com/peppone-choi/opensamguk/issues/466) — DB 전환(`game_server` 테이블) 완료, 잔여는 운영 환경에서의 무재시작 반영 검증
- 서버·DB·world 실행 환경 격리 운영: [#452](https://github.com/peppone-choi/opensamguk/issues/452)
- 군현·부대·명령 관리 UI: [#213](https://github.com/peppone-choi/opensamguk/issues/213)

각 항목의 최신 상태는 연결된 공개 이슈와 Jira를 함께 확인합니다.
