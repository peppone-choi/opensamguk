# 세계·지도·커맨드 계획 교차 비평

Scope: app/, common/, infra/, logic/, web/, tools/ 변경이 함께 존재하는 작업트리에서 이번 계획·사료 감사기·지도 데이터 계약을 검토하고, 기존 사용자 구현 변경은 별도 소유 작업으로 격리한다.
Verdict: quarantined-with-proof
Proof: 독립 읽기 전용 감사 4건의 지적을 정본에 반영했고, 이번 변경과 무관한 기존 app/common/infra/logic/web/assets 변경은 되돌리거나 재작성하지 않았다. 전체 현재 작업트리는 backend XML 4,737 tests failures=0 errors=0, gateway/game typecheck 통과, web/game 76 files 431 tests 통과로 관찰했다.

## 독립 감사 구성

- 지도·parser 감사: 현재 1,076 결손의 정확한 원인, 105/1,180 구조 검출, 안평국 13/12 불일치,
  `龜茲屬國` 가짜 군국 승격, 현재 780 선정 산술의 불안정성을 공격적으로 검토했다.
- 외부 세계 감사: 영구 활성 기간, 대방군·유구의 시대 오류, 왜 여정 압축, 서역 0건,
  이동 세력의 도시화를 검토했다.
- core2026 비교 감사: command schema·durable result·release 운영의 재사용 가능성과 즉시 이동·API write·
  컨테이너 내부 build의 부적합을 비교했다. 현재 1,783 후보 edge도 독립 재계수했다.
- 게임 흐름 감사: samnet 공개 표면, 묘삼 역사 자료, 현재 OpenSamguk 제품 spec을 비교하고
  칠랑섭·묘삼 현행 미확인 항목을 `UNKNOWN`으로 남겼다.

## 발견과 반영

1. **fix-required:** “1,180 중 104개 원문 미검출”은 틀렸다. 원문 구조는 105/1,180을 전수
   검출하며 기존 좌표 결합만 1,076이다. 감사기와 문서를 `104 join gaps`로 고쳤다.
2. **fix-required:** 780개 개별 도시를 이미 역사 정본으로 동결할 수 없다. 총 목표 780은 유지하되
   reviewed selection manifest와 save migration을 요구하도록 고쳤다.
3. **fix-required:** 1,778개 고정 edge 전제가 현재 작업트리 1,783개와 충돌한다. 숫자가 아니라 승인
   snapshot count+hash와 corridor provenance를 완료 조건으로 바꿨다.
4. **fix-required:** 아이소 격자와 자동 직선 도로 문서가 살아 있었다. 두 시각 spec을 supersede하고
   승인 geometry 뒤의 지리 기반 2D/2.5D 작업면으로 순서를 바꿨다.
5. **fix-required:** 중국 밖을 고정 도시로만 다루면 시간·위치·여정이 왜곡된다. 장소·세력권·여정·
   remote gate를 분리하고 외부 권역 전용 acceptance criteria를 만들었다.
6. **fix-required:** CHE-plus식 명령 확장은 1천여 행정단위에서 미시관리로 붕괴한다. typed travel·convoy·
   operation 명령과 위임, 귀환 causal summary, 살아 있는 편년체를 제품 핵심 루프로 승격했다.

위 여섯 지적은 모두 로컬 master plan, ADR/spec amendment, Jira OPENSAM-213~215·225~228,
GitHub #473~475·#491~494에 반영되어 열린 `fix-required`가 없다.

## 격리 범위와 잔여 위험

- 기존 사용자 변경인 시나리오·`common/src`의 HanCityConst/HanGateIndex·unitset·아이콘 생성물은
  이번 계획 작업에서 편집하거나 의미를 승인하지 않았다. 테스트 통과는 회귀 증거이지 역사 데이터
  승인 증거가 아니다.
- 현재 780 node와 1,783 edge는 후보 snapshot이다. OPENSAM-225/213의 manifest·provenance review 전에는
  운영 정본이나 도로로 승격하지 않는다.
- `corepack` 실행 파일은 현재 셸에 없었다. 동일 pnpm 검증은 설치된 `pnpm`으로 통과했으며 환경 차이는
  제품 결함으로 오인하지 않는다.
- 칠랑섭 내부 동작과 묘삼 현행 운영은 확인하지 못했다. 후속 증거가 생기기 전 요구사항에 사용하지 않는다.
