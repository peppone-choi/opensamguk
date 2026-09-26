# 2026-09-26 #917 — 관리자 시나리오 목록을 importer 가 받는 것만

## 범위

- `ScenarioCatalogService`가 옛 삼모 역사·IF 시나리오 15개(`scenario_1010`–`scenario_1120`)를 관리자 리셋 목록에 내보내고 있었다. 15개 모두 `worldFormat`·`ruleProfile`·휘하 선언이 없어 `ScenarioImporter.validateFreshProfile`이 거절한다. 고르면 시드가 실패한다.
- 목록을 검증된 `scenario_990002` 하나로 좁혔다. 190 역사 시나리오(`scenario_3190`)는 S4 F1(#969)이 main에 들어온 뒤 별도 PR로 더한다.
- JSON 리소스는 지우지 않았다. 삼모 시나리오 JSON 삭제는 #917 다음 슬라이스에서 테스트·도구 참조와 함께 한다.

## 검증

- `./gradlew :app:gateway-api:test --tests ScenarioCatalogServiceTest`(JDK 21) 1/1, 결과 XML이 이번 실행에서 새로 생김.
- 적색 프로브: 목록에 `scenario_1010`을 되살리면 목록 단언(16행)에서 실패.
- `naming_lint.py` 기준선 그대로.

근거 감사: 메타 `reports/opensamguk/tasks/2026-09-26-gateway-scenario-catalog-audit.md`(1층이 적어 둔 구현 경계와 같다).
