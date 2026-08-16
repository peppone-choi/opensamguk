# 옵션 IP 초상 세트 전량 제거(메인 레포 쪽) — 리뷰

Scope: `data/extracted/scenario/` 16개 시나리오 JSON 삭제 + `_meta.json` 항목 제거, `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json` 핀 SHA 갱신, 감사 문서·LEDGER 각주.
Verdict: fix-required

## 1. 배경

`opensamguk-images` 레포에서 §2-2의 10개 프랜차이즈/실존인물 세트(2,335장)를 히스토리까지 제거하는 결정과 짝을 이룬다. 이 브랜치는 메인 레포 쪽 두 가지를 처리한다:

1. 그 세트를 참조하는 시나리오 데이터 16개 삭제.
2. `opensamguk-images` 히스토리 재작성으로 무효가 된 핀 고정 SHA(`shared-manifest.json`)를 새 SHA로 갱신.

## 2. 전수 스캔 근거

10개 세트명 각각 `grep -rl <세트명> data/extracted/scenario/`로 검색해 코드를 확정했다(사용자가 사전 확인한 목록과 정확히 일치, 추가/누락 없음). 삼국지 역사 시나리오(0·1·9xx·10xx·11xx)는 이 10개 세트를 하나도 참조하지 않는다. `_meta.json`의 `scenarios` 배열에서 동일한 16개 코드를 확인 후 제거, `count` 81→65.

`삼국지6`(1장, 코에이 삼국지6)은 어떤 시나리오도 참조하지 않아 메인 레포 쪽에 삭제할 시나리오가 없다.

조율자(코디네이터)가 이미지 레포 쪽에서 `game/map/pokemon_v1/`(시나리오 2210 전용, 고아)도 추가로 지웠다고 알려왔고, `game/map/ludo_rathowm/`는 시나리오 2180이 아직 참조해서 남겼다고 알려왔다. `scenario_2180.json`의 `iconPath`는 `"."`(루트 아이콘)이라 이번에 삭제하는 10개 세트 중 어느 것도 참조하지 않는다 — 확인 완료, 2180은 삭제 대상 아님.

## 3. 참조 조사 — 코드/설정

`ScenarioImporter`/`ScenarioSeedRunner`/테스트/`docker-compose*.yml`/`.env.example`을 대상으로 16개 코드(`2140`·`2141`·`2900`·`2901`·`2903`·`2904`·`2200`·`2171`·`2210`·`2130`·`2131`·`2800`·`2801`·`2300`·`2600`·`2601`)를 전수 grep했다. 매칭 0건 — 애초에 classpath 시드 목록(`infra/src/main/resources/scenario/`, 31개: 0·1·2·1010~1120·900~914)에 없었고 `SCENARIO_CODE` 기본값도 `scenario_1010`(`docker-compose.production.yml:67`)이다. `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/a2-scenario-seed-sha256.txt`에 과거 sha256 스냅샷이 남아 있으나 그 루프는 이미 닫힌 정적 기록이라 손대지 않았다.

## 4. 핀 SHA 갱신

조율자가 이미지 레포 히스토리 재작성 완료 및 새 SHA(`05842c61132fd5a71268fd9babd80ba74e27be62`, 태그 `v2026.05.21`이 가리키는 새 커밋)를 전달했다. `shared-manifest.json`의 두 `existing_shared_cdn` 항목(`1001`, `default`)의 `source_revision`·`delivery_url`을 갱신했다(`sha256`/`portrait_asset_id`는 파일 바이트 불변이라 그대로). `docs/loops/opensam-91-profile-icon/LEDGER.md:85`는 과거 사실 기록이라 값을 바꾸지 않고 히스토리 재작성으로 무효화됐다는 각주만 추가했다.

두 URL 모두 jsDelivr·`raw.githubusercontent.com` 양쪽에서 `curl -s -o /dev/null -w "%{http_code}"` = **200** 확인(4/4).

## 5. 하지 않은 것

- `web/game` `pnpm exec vitest run`은 시간 제약으로 이 리뷰 작성 시점에 결과를 확보하지 못했다 — 별도 검증 필요.
- 백엔드 `:infra:test :app:game-engine:test` 결과는 진행 중이며 이 문서에는 반영되지 않았다.
- **자기 승인 금지 규칙에 따라 Verdict는 `fix-required`로 둔다.** 독립 리뷰어(별도 에이전트/제공자)가 PHP 증거·테스트·핀 SHA 200 확인·`shared-manifest.json` 스키마 정합을 재확인하고 `cleared`로 전환해야 한다.
