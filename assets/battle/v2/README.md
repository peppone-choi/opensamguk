# V2 전술 전투 에셋 인수인계

이 디렉터리는 V2 2D/2.5D 전투용 **표현 에셋 후보**만 보관한다. 병력 수치, 이동, 충돌, 고저차, 시야, 피해, 사기와 같은 게임플레이 판정은 픽셀에서 만들거나 추론하지 않는다. 해당 판정의 정본은 전투 시뮬레이션과 그 데이터 계약이다.

모든 `*_CANDIDATE` 상태는 프로덕션 승인 상태가 아니다. `RAW_IDENTITY_MASTER`도 런타임 스프라이트가 아니라 원본 정체성 마스터다. 런타임 로더는 매니페스트의 `status`, `count`, 경로와 해시를 검증한 뒤에만 후보를 소비해야 한다.

## 에셋 범위

| 영역 | 원본/런타임 경로 | 매니페스트 | 범위 | 현재 계약 |
| --- | --- | --- | --- | --- |
| 병종 원본 | `units/source/*.png` | `units/source/manifest.json` | 105 병종 변형의 1024px 원본 마스터 | `RAW_IDENTITY_MASTER` |
| 병종 정적 런타임 | `units/sprites/*.png` | `units/sprites/manifest.json` | 105 병종 변형의 256px idle 전용 후보 | `STATIC_RUNTIME_SPRITE_CANDIDATE` |
| 지형 코어 | `terrain/source/*.png`, `terrain/tiles/*.png` | `terrain/manifest.json` | 8개 2×2 소스 시트에서 추출한 32개 셀/모듈 | `STATIC_TERRAIN_CORE_CANDIDATE` |
| 전투 이펙트 | `effects/source/*.png`, `effects/atlases/*.png` | `effects/manifest.json` | 물리·화염 계열의 애니메이션 상태 16개 | `RUNTIME_EFFECT_CANDIDATE` |

표의 병종 원본 105개와 지형 32개는 각각의 현재 매니페스트가 선언한 수량이다. 병종 정적 스프라이트와 이펙트 16개는 컴파일러의 전체 발행 계약이며, 해당 런타임 매니페스트가 없으면 아직 발행된 것으로 취급하지 않는다.

## 소비 규칙

- 병종은 변형당 idle 프레임 하나만 제공한다. 8방향·이동·공격·피격·사망은 병종별로 중복 제작하지 않으며, 후속 공통 directional/action chassis가 원본 정체성 마스터와 결합할 때까지 보류한다.
- 병종 원본과 런타임 매니페스트의 `simulationAuthority`도 항상 `false`다. 병종 실루엣·무기·색상은 전투 수치, 상성 또는 명령 가능 여부의 정본이 아니다.
- 병종 런타임 매니페스트는 `units/sprites/visual-qa.json`의 105개 원본·런타임 해시와 `identity`·`silhouetteAt64px` 판정이 전부 일치할 때만 발행한다.
- 지형 매니페스트의 `simulationAuthority`는 항상 `false`다. 지형은 시각 레이어이며, 권장 아트 셀 크기는 4m다. 현재 지면 셀은 모서리 검사 결과에 따라 비타일형 단일 모듈로 선언되고, 도로·수계·도하 모듈의 방향·소켓·전이도 아직 미지정 상태다. 타일 연결, 이동 비용, 방어 보정, LOS, 엄폐 및 충돌은 엔진의 논리 격자에서만 결정한다.
- 이펙트 매니페스트의 `visualOnly`는 항상 `true`다. 화살 명중, 불, 먼지, 물보라, 연기는 이벤트를 보여줄 뿐 전투 결과를 변경하지 않는다.
- 지형과 이펙트는 레이어 순서·알파·앵커를 매니페스트대로 렌더한다. 파일명이나 색상으로 병종 분류, 타일 비용, 효과 피해량을 역산하지 않는다.

## 생성·컴파일

저장소 루트에서 이미 지문이 결합된 전체 세트를 검증·발행한다.

```sh
PPGEN=/usr/bin/false node tools/assets/generate-v2-roster-static-sprites.mjs
node tools/assets/compile-v2-unit-static-sprites.mjs
node tools/assets/compile-v2-terrain-core.mjs
node tools/assets/compile-v2-battle-effects.mjs --force --verify-no-fallback
```

첫 명령은 공급자를 호출하지 않고 105개 원본과 `units/source/source-receipt-ledger.v1.json`의 현재 카탈로그·프롬프트·요청·생성 스크립트·PNG 지문을 검증해 `units/source/manifest.json`을 재발행한다. `build/` 영수증은 이전 또는 새 생성 때만 쓰는 선택적 캐시이며 릴리스 검증의 필수 입력이 아니다. 새 원본 생성은 반드시 `--force --only <slug>`로 명시하며, 과거 v1 전체 세트의 일회성 이전만 `--adopt-existing`을 사용한다. 두 번째 명령은 `sprite-gen`의 결정적 component-row 추출로 idle 정적 스프라이트를 검증하고, 해시가 일치하는 105개 개별 시각 QA가 있을 때만 `units/sprites/manifest.json`을 발행한다. 세 번째 명령은 준비된 8개 2×2 지형 소스 시트를 결정적으로 크롭·컷아웃하고 모서리·배치 메타데이터를 계산한다. 네 번째 명령은 기존 작업 디렉터리를 명시적으로 재구축하고, 슬롯 폴백 없는 component 추출을 실제 발행 경로와 독립 사전 검사 모두에서 강제한 뒤 이펙트 아틀라스·추적 영수증·`effects/manifest.json`을 만든다.

`--dry-run`, `--only`, `--force`의 정확한 사용법은 각 도구의 `--help`를 따른다. 부분 실행은 전체 매니페스트를 발행하지 않을 수 있으므로, 런타임 반영에는 인수/출력 수가 모두 충족된 전체 실행만 사용한다.

## 재생성 전제

- Node.js와 현재 병종 카탈로그가 필요하다.
- 원본 병종 재생성에는 로컬 PerfectPixel `ppgen` 설치와 외부 공급자 설정이 필요하다. 공급자 인증 정보는 이 저장소, 프롬프트, 매니페스트 또는 로그에 넣지 않는다.
- 현재 지형 원본 시트의 최초 생성 공급자와 모델은 확인할 영수증이 없어 `adopted-existing`으로만 기록한다. 향후 재생성은 승인된 built-in imagegen 워크플로로 수행하며, 현재 컴파일러는 추적된 기존 소스를 잘라낼 뿐 이미지 생성 서비스에 인증하지 않는다.
- 병종 런타임·지형·이펙트 컴파일에는 `sprite-gen`과 그 전용 Python 환경이 필요하다. 기본 위치가 다르면 `SPRITE_GEN_ROOT`로 명시한다.
- 출력 PNG를 수동 편집했으면 관련 매니페스트의 해시와 크기를 신뢰하지 말고 해당 전체 컴파일러를 다시 실행한다.

## 출처와 IP 경계

병종 원본 중 생성 영수증이 있는 항목은 PerfectPixel `ppgen`, 나머지는 검증된 기존 v1 후보에서 이전됐다. 지형 소스의 최초 생성 경로는 확인되지 않아 `adopted-existing`으로 기록한다. 런타임 스프라이트·지형 컷아웃·이펙트 아틀라스는 `sprite-gen`의 결정적 추출/컷아웃 단계에서 나왔다. 모든 프롬프트와 아트 방향은 후한 말·삼국 시대의 오리지널 역사적 디자인을 목표로 하며, 텍스트·현대 장비·판타지와 특정 프랜차이즈 모방을 금지한다. Total War, Koei 또는 다른 게임의 이미지·UI·실루엣을 복사하거나 참조 에셋으로 사용하지 않는다.

## 역사 자료 참조 경계

아래의 공식 박물관·유산 자료는 건축물, 수송물, 수로, 요새, 농경지의 **계열과 실루엣**을 고증하는 데만 사용한다. 어떤 이미지도 이 저장소의 에셋으로 복사하지 않으며, 자료 사진의 정확한 치수·비율을 런타임 셀 크기나 충돌 규칙으로 전사하지 않는다.

- [메트로폴리탄 미술관: 한대 건축 모형·곡창·우물·망루](https://www.metmuseum.org/de/perspectives/modeling-the-world-ancient-architectural-models)
- [허난박물원: 한대 다리·수레·배 벽돌](https://english.chnmus.net/en/collection/details.html?id=418104519539589453)
- [허난박물원: 한대 성문·건물·수레·측백나무 벽돌](https://english.chnmus.net/en/collection/details.html?id=418145469164542768)
- [UNESCO: 장안–톈산 회랑의 요새·봉수대·길](https://whc.unesco.org/en/list/1442/)
- [규슈국립박물관: 삼국 시대 화물·여객선과 논·연못 전시](https://www.kyuhaku.jp/en/exhibition/exhibition_s56.html)
