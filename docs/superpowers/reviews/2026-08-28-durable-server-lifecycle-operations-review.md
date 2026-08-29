# Durable server lifecycle operations review

## 범위

`opensamguk`과 `opensamguk-docker`의 operation store, journal/restart repair, gateway transition reconciliation, admin UI polling, 직접 workflow polling을 작업별 독립 reviewer가 검토했다.

## 해결된 중요 finding

- 기존 operation store의 0600 권한, secret-safe public message, rename 이후 fsync commit point.
- prepared create repair 및 post-reservation 실패의 terminal convergence와 zero-Docker restart recovery.
- 신규 gateway transition과 reclaimed transition 구분, 완료 operation ID의 payload/kind/subject 재사용 충돌.
- in-flight fetch를 포함한 UI 10분 absolute deadline과 abort 전파.
- restart replay의 optional job ID, destructive POST 전 deployer capability/runtime preflight.

모든 중요 finding은 focused RED/GREEN 수정 후 scoped re-review에서 해결됨으로 승인됐다.

## 검증 요약

- Deployer full Go test/vet/build: PASS.
- Gateway full 224/224, infra full 247/247: PASS on Task 3 tree.
- Admin web full 207/207, typecheck: PASS.
- Workflow helper/YAML/shell/focused Go contracts: PASS.

Task 5의 workflow-only 변경 후 full Gradle 재실행은 기존 V32 migration PostgreSQL socket 대기로 중단됐고, lifecycle subset 68/68은 재통과했다.

## 남은 위험

- 실제 deployer/gateway/browser와 disposable server를 함께 사용한 destructive E2E smoke는 수행하지 않았다.
- 배포되지 않았으며 deployer를 먼저 rollout해야 한다.
