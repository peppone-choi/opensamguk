# NOTICE

OpenSamguk 의 소스 코드 · 문서 · 프로젝트가 직접 만든 자산은 [MIT 라이선스](LICENSE)입니다.
이 문서는 저장소에 함께 들어 있는 외부 저작물의 원 고지와, **MIT 범위 밖**에 있는 자료를 밝힙니다.

> MIT 범위 밖 자료(아래 3절)에 대해 이 저장소는 어떤 이용 · 재배포 권리도 부여하지 않으며, 원저작물의 권리를 주장하지 않습니다.
> 그 자료를 쓰려면 각 원저작권자의 조건을 따로 확인해야 합니다.

## 1. MIT 라이선스로 받은 코드 · 데이터

OpenSamguk 은 HideD 님과 devsam 기여자들의 삼국지 모의전투 프로젝트에서 출발했습니다.
아래 두 저장소는 MIT 라이선스이며, 원 고지를 그대로 옮깁니다.

### 1-1. devsam/core

- 원본: <https://storage.hided.net/gitea/devsam/core> (LICENSE 확인 커밋 `4de7ebec17a722d516608dbb987467f1a451dada`, 2026-03-04)
- 쓴 곳: `data/extracted/**`(시나리오 · 상수 추출), 초기 규칙 이식의 개발 이력

```text
The MIT License

Copyright (c) 2023 Hide_D, 62che

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### 1-2. devsam/core2026

- 원본: <https://gitea.hided.net/devsam/core2026> (LICENSE 커밋 `36877e5a38fbf79c7454b1bcf36c8b5c1add9ac1`, 2025-12-27)
- 쓴 곳: 일부 공통 상수 · 난수 · 명령 규칙을 옮길 때의 대조 기준(해당 소스 파일 주석에 표시)

```text
MIT License

Copyright (c) 2025 devsam

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## 2. 글꼴 — SIL Open Font License 1.1

웹 화면의 글꼴 셋(Noto Serif KR · JetBrains Mono · Pretendard)은 OFL-1.1 입니다.
원문과 출처 표는 [`web/licenses/`](web/licenses/README.md)에 있습니다. 글꼴은 MIT 가 아니라 OFL-1.1 을 따릅니다.

## 3. MIT 범위 밖 자료

아래 자료는 MIT 로 배포하지 않습니다. 이 저장소는 이 자료들에 대해 재배포 권리를 부여하지 않으며, 원저작권을 주장하지 않습니다.
게임 화면에 쓰는 것은 저장소 소유자의 판단이고, 제3자에게 권리를 넘기는 것이 아닙니다.

### 3-1. 「제갈공명 와룡전」 파생 지도 · 전장 자료

- 경로: `data/map/waryong/**`, `data/battle/waryong/**`, `web/game/public/map/waryong/**`, `web/game/public/battle/waryong/**`,
  `web/gateway/public/map/waryong/**`, `web/gateway/public/battle/waryong/**`
- 원작 게임의 타일 · 표식 · 지형 자료에서 만든 파생물입니다. 원본 파일과 변환 도구는 이 저장소에 없습니다.
- 각 폴더의 `NOTICE.md` 와 원천 저장소 [opensamguk-images](https://github.com/peppone-choi/opensamguk-images)의
  `waryong/LICENSE-NOTICE.md` 가 경계를 설명합니다.

### 3-2. CHGIS 파생 역사 지리 자료

- 경로(CHGIS 를 직접 읽어 만든 것):
  - `data/map/external-places.json`(CHGIS · TGAZ 지명과 좌표)
  - `data/map/han-780-v1-tiles.json` · `data/map/province-tiles.json`(縣 · 郡 칸 배치, `tools/map/build_tile_grid.py`)
  - `data/map/han-administrative-history.json`(소속 대조, `tools/map/build_han_parent_reconciliation.py`)
  - `data/curated/han/**` 중 CHGIS 를 근거로 한 행
- 경로(위 자료나 CHGIS 기반 중간 산출물 `province-tiles.json` 을 입력으로 만든 것):
  - `data/map/han-*.json`(물길 · 도로 · 보급 연결 · 州 색인 · 전략 거점 · 시나리오 소속 등)과 `data/map/han-province-id-registry.tsv`
  - 지도 배포물: `data/map/han-world-v3-manifest-v1.json`, `data/map/han-world-artifacts-v1/**`,
    `data/map/han-world-v3-*-artifacts-v1/**`, `data/map/province-world-*-artifacts/**`
  - 런타임 지도: `infra/src/main/resources/map/han-world-v3.json`, `infra/src/main/resources/map/han-780-v1.json`
  - 중간 산출물은 `data/map/province-tiles.json`에 커밋돼 있습니다.
- 원 자료: CHGIS(China Historical GIS) Version 6, TGAZ(Temporal Gazetteer)
- 필수 인용(CHGIS V6 EULA (4)): "CHGIS Version 6." (c) Fairbank Center for Chinese Studies and the Institute for Chinese Historical Geography at Fudan University, Dec 2016.
- 바꾼 것: 후한 시기 군현 치소 · 소속을 골라 게임 격자(타일)와 행정 계층으로 다시 묶고, 사료 대조로 일부 소속 · 위치를 고쳤습니다.
  원본 shapefile · DBF · 좌표 원 자료는 이 저장소에 없습니다. 변환 도구는 `tools/map/` 에 있습니다.
- CHGIS 자료의 이용 조건은 CHGIS 의 EULA 를 따릅니다. 이 저장소의 MIT 라이선스는 CHGIS 파생 자료에 이용 · 재배포 권리를 주지 않습니다.

### 3-3. 「삼국지 14」(코에이테크모) 기준 자료

- 경로: `infra/src/main/resources/scenario/scenario_3190.json`(190년 시나리오 인물 능력 · 생몰 · 등장 등),
  그 시험용 사본(`app/game-engine/src/test/resources/scenario/scenario_3190_test.json`), `data/reference/rtk14/**`(이름 · 분류 참고 원장)
- 게임 밸런스의 기준으로 삼은 자료입니다. 원 게임 · 원 자료의 권리는 원저작권자에게 있습니다.
  자료 경계는 [`docs/data/scenario-3190-source-boundary.md`](docs/data/scenario-3190-source-boundary.md)에 있습니다.

### 3-4. 지운 devsam 그림 파생물(git 이력에만 있음)

- 지금 저장소의 깃발 `web/game/public/flags/**`, `web/gateway/public/flags/**` 는 2026-08-17(#418)부터 `tools/assets/build_flag_assets.py` 가 입력 그림 없이 그린 자작물입니다. 다시 돌리면 같은 바이트가 나오고, MIT 를 따릅니다.
- 그 전에 있던 devsam 그림 저장소(devsam/image) 파생 깃발 16장과 아이콘 GIF 28장은 #418 에서 지웠습니다. git 이력(`6017ad235` · `80b4a47ac` · `cd81083ce` 등)에만 남아 있습니다.
  - 원본 저장소에 라이선스 표기가 없어, 이력 속 그 파일들은 MIT 범위 밖입니다.

## 4. 그 밖

- 사서(『三國志』 · 『後漢書』 · 『資治通鑑』 등) 인용은 저작권이 소멸한 고전 원문입니다.
- 웹 그림 대부분은 그림 저장소 [opensamguk-images](https://github.com/peppone-choi/opensamguk-images)의 export 사본입니다: `web/*/public/{city,status,icons,illustrations,map/markers,stratagem-cards}/**`, `logo-wordmark*`, `web/*/app/{favicon.ico,icon.png,apple-icon.png}`.
  - 직접 그린 것(SVG · 절차 생성)과 생성형 이미지 모델로 만든 것이 섞여 있습니다. 어느 것이 어느 쪽인지와 원화 기록은 그 저장소의 `ASSET-EXPORTS.md`, `assets/**/README.md`, `assets/brand/WORDMARK.md`, `originals/stratagem-cards/provenance.json` 에 있습니다.
- 상태 아이콘 중 황제 거처 · 황제 NPC 표식(`web/*/public/status/**/imperial-*.png`)은 『삼국지 7』(코에이테크모) 전국지도 献帝 필터의 16×16 흉상을 **구도 참조로만** 삼아 이미지 생성 모델로 새로 그린 것입니다. 참조 그림은 이 저장소에 없습니다.
- 지형 높이 `web/*/public/map/elevation/**` 는 NOAA NCEI ETOPO1 Ice Surface(1 arc-minute, 미국 연방정부 공공 저작물)를 재표본한 것입니다.
  - 출처: Amante, C. and B.W. Eakins, 2009. ETOPO1 1 Arc-Minute Global Relief Model: Procedures, Data Sources and Analysis. NOAA Technical Memorandum NESDIS NGDC-24. doi:10.7289/V5C8276M
- 그 밖에 이 저장소의 생성기(`tools/assets/**`, `tools/map/**`)가 입력 그림 없이 만든 그림(지도 타일 · 표식 · 시험 고정물 등)은 MIT 를 따릅니다.
- 의존 패키지의 라이선스는 각 패키지가 동봉한 고지를 따릅니다(`web/pnpm-lock.yaml`, Gradle 의존성).

이 문서에 빠진 외부 저작물을 찾으면 이슈 또는 [SECURITY.md](SECURITY.md)의 연락처로 알려 주세요.
