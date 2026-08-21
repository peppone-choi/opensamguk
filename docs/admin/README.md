# 오픈삼국 관리자 매뉴얼

> 상태: 현재 Gateway·게임 관리자 화면 기준
> 마지막 검토: 2026-08-20

관리 표면은 두 곳입니다.

| 표면 | 대표 경로 | 책임 |
|---|---|---|
| Gateway 관리자 | `/admin` | 회원, 게시판, 서버 생성·리셋·삭제, 버전, 공용·서버 환경 |
| 게임 관리자 | `/game/admin1`, `admin2`, `admin5`, `admin7`, `admin8`, `tournament-admin` | 현재 월드 설정·장수 조치·통계·로그·외교·토너먼트 |

브라우저에서 탭이 보이는 것은 편의 기능입니다. 실제 권한은 Gateway의 `ROLE_ADMIN`, game-api의 인증 주체,
장수 소유권·직책과 각 API의 서버 검사가 결정합니다.

## 먼저 읽을 문서

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

## 현재 운영 제어면

프로덕션 정본은 별도 [`opensamguk-docker`](https://github.com/peppone-choi/opensamguk-docker) 저장소의
shared/server/deployer 모델입니다. 이 앱 저장소의 `docker-compose.production.yml`과 `scripts/deploy.sh`는 호환
표면이며 멀티서버 운영 정본이 아닙니다.

## 아직 완료로 보지 않는 기능

- 공지 고정·soft-delete를 포함한 게시판 관리자 기능: [#223](https://github.com/peppone-choi/opensamguk/issues/223)
- 서버 레지스트리 DB화와 무재시작 반영: [#466](https://github.com/peppone-choi/opensamguk/issues/466) — DB 전환(`game_server` 테이블) 완료, 잔여는 운영 환경에서의 무재시작 반영 검증
- v1/v2 서버·DB·world 격리 운영: [#452](https://github.com/peppone-choi/opensamguk/issues/452)
- v2 군현·부대·명령 관리 UI: [#213](https://github.com/peppone-choi/opensamguk/issues/213)

GitHub issue는 확인했지만 Jira live 상태는 커넥터 권한 밖이므로 `UNKNOWN`입니다.
