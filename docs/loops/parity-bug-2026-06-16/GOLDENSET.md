# GOLDENSET — parity-bug-2026-06-16 (초안 — 승인 대기)

루프: 패러티 갭 + 버그 + 5스탯(정치·매력) + 프론트엔드 + 장기시뮬 + 자동 푸시.
고정 시험지. 유저 승인 후 동결. 파일 1줄당 1문항(Y/N). 채점 = 제안 컨텍스트 없는 fresh 서브에이전트.

> 패러티 항목(1·2·6)은 **기존 골든 게이트가 곧 골든셋**이다 — 새로 만들지 않고 절대 완화하지 않는다 (CLAUDE.md 패러티 규율 5).
> 본 파일은 그 게이트들 + 신규 버그/기능의 Y/N 인수 기준을 한 데 모은 **루프 시험지**다.

| # | 문항 (Y/N) | 채점 방법 | 출처/연결 |
|---|---|---|---|
| 1 | 전체 백엔드 게이트(`:common :logic :infra :app:game-engine :app:game-api`) failures+errors = 0? | `tools/parity/gate.sh backend` → test XML | CLAUDE.md backend gate |
| 2 | 모든 기존 골든 리플레이 게이트(`*GoldenTest`/`*ReplayGateTest`/`*GateTest`) draw-for-draw green, 완화 0? | gate 실행 + git diff golden 리소스 = 0 | 패러티 규율 1–6 |
| 3 | 어드민 페이지에서 서버 목록/버전/상태 정보가 200 + 비어있지 않게 로드? | E2E(/browse) 또는 API 200 + payload 비어있지 않음 | 유저 신고 #2 |
| 4 | 장기 시뮬(≥N턴) 후 최소 1개 나라 생존 — 전세계 무국가(empty world)로 수렴하지 않음? | 엔진 N턴 구동 후 nation count > 0 (PHP 오라클 대비) | 유저 신고 #1 |
| 5 | 멸망→방랑군→거병/건국 재건국 경로 동작 — 나라 수가 단조감소만 하지 않음? | 장기시뮬 로그에서 신규 건국 ≥1 관측 (PHP 대비) | 유저 신고 #1 근본원인 |
| 6 | 5스탯 divergence 플래그-OFF 경로가 devsam-baseline 골든과 draw-for-draw 동일(통무지 패러티 불변)? | flag-off 골든 게이트 green | CLAUDE.md 5스탯 divergence |
| 7 | 5스탯 플래그-ON(정치·매력) 동작이 **신규** divergence 골든으로 별도 신설·green (기존 골든 재생성 0)? | 신규 divergence 골든 green + 기존 골든 diff 0 | 유저 신고 "정치 매력 넣고" |
| 8 | 신규 프론트엔드 페이지/기능이 `tsc` 통과 + 렌더(읽기/제출 경로 동작)? | `pnpm tsc` + /browse 렌더 확인 | 유저 "프론트엔드 추가" |
| 9 | main push 전 CI green (게이트 red면 푸시 금지)? | CI 상태 green 확인 | feedback_auto_merge_deploy |
| 10 | 배포 후 prod 헬스 200 + 턴 전진 확인? | HealthCheck 200 + world_state turn 증가 | feedback_auto_merge_deploy |

> 0바퀴(베이스라인) 채점은 승인 직후·첫 변경 전에 실행하고 LEDGER 0바퀴 행으로 기록한다.
