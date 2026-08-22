# OPENSAM-229 deploy service inventory review

Scope: `.github/workflows/` 배포 workflow의 Compose inventory 검사, `tools/ops/` 회귀 계약, `docs/superpowers/reviews/` 검증 기록.

Verdict: cleared

- Production evidence: main run 32587798358의 첫 deploy job은 docker main에 board-api가 있는데도 service gate에서 실패했고, 동일 SHA failed-job 재실행은 deploy, pin 보존, health와 등록 서버 검증까지 성공했다.
- Mechanism toggle: `pipefail` 아래 조기 종료하는 quiet grep pipeline은 upstream SIGPIPE로 exit 141을 냈고, 동일 출력을 먼저 변수에 캡처한 검사는 exit 0이었다.
- Red: 새 contract test가 `$COMPOSE config --services | grep -q`를 발견해 1 failure로 실패했다.
- Green: service 목록은 한 번 캡처하고, 전체 config는 quiet grep 대신 출력을 끝까지 소비하는 pipeline으로 검사해 Compose 실패 전파와 secret 체류 최소화를 함께 유지했다. contract test는 CI에 연결했다.
- Regression: JWT rollout contract, V2 sandbox compose contract, `git diff --check` 통과.
- Operational state: 원래 최종 main SHA `306e179c...`는 재실행으로 이미 정상 배포됐으며, 이 변경은 같은 비결정적 gate의 재발을 막는다.
