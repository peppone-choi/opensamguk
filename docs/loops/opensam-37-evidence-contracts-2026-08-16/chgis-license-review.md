# T1-K02 — CHGIS 라이선스 검토 (번들 전)

- 티켓: OPENSAM-37 [G0-A②] / backlog `04-systems-micro.md` 그룹 K, L130
- 검토일: **2026-08-16**
- 결론 한 줄: **번들 금지.** 제품 자산으로 CHGIS 데이터를 넣으려면 CHGIS Management Committee의
  서면 계약이 유일한 경로다. Harvard Dataverse의 `CC0 1.0` 표기는 같은 데이터셋에 동봉된 EULA와
  정면으로 충돌하며 그 표기의 근거는 **UNKNOWN**이므로 근거로 쓰지 않는다.

## 1. 확인한 출처와 원문 인용

### 1-1. https://chgis.fas.harvard.edu/pages/intro/
라이선스 절 없음. 관련 문구는 홍보 문장뿐:

> "a new digital product for free distribution to scholars without restriction"
> "a no-cost GIS platform for use in teaching, research, and publications"
> "© CHGIS 2001 -"

"without restriction"은 *scholars*를 주어로 한 홍보 문구이며 아래 실제 약관들과 모순된다.
라이선스 근거로 쓸 수 없다.

### 1-2. 버전 페이지 `chgis.fas.harvard.edu/data/chgis/v{1,3,4,5,6}/`
v1~v6 전 버전에 동일한 한 줄이 있다:

> "free for academic research, no commercial use, resale, or redistribution permitted."

저작권 표기만 버전별로 다르다(v1~v4 Harvard Yenching Institute, v5~v6 Fairbank Center + Fudan
Center for Historical Geographical Studies).
`/pages/download/`는 404, `/data/`에는 약관 문구 없음.

### 1-3. 정본 — CHGIS V6 EULA
`https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/FDLFJ3` (DOI
10.7910/DVN/FDLFJ3, "CHGIS V6 EULA", Berman, Lex, 2016, 파일 `CHGIS_Version_6_EULA.pdf`).
동일 EULA 사본(`CHGIS_V6_EULA.txt`)이 각 V6 데이터셋 안에 동봉돼 있다(DataCite로
`10.7910/DVN/ST5KKM/78ODJ9`, `2K4FHX/B14K0P`, `HHVVHX/M8FSCU`, `0P89R9/NCU7PK` 확인).

원문 인용:

> "(1) … **The CHGIS data is not for sale or license by any third party.**"

> "(3) terms of use: **Access to, and use of, these data is restricted to non-commercial use for
> academic research and educational purposes. Commercial use requires a separate CHGIS Commercial
> Data License agreement, which may be obtained only from the CHGIS Management Committee. Any
> organization or individual that would like to develop commercial or revenue generating use of the
> CHGIS Datasets, must first obtain a licensing agreement from CHGIS Management Committee.**"

> "(4) citation: … Mandatory Citation: \"CHGIS Version 6.\" (c) Fairbank Center for Chinese Studies
> and the Institute for Chinese Historical Geography at Fudan University, Dec 2016.\" You may use
> portions of the CHGIS data in your research and publications, but … Clearly describe any changes
> you have made to the CHGIS Data that are included in your work."

> "(5) redistribution: **You may not incorporate the entirety of CHGIS Data Layers in a work of your
> own intended for public dissemination, whether modified or not, without the express permission of
> the Management Committee in a separate License Agreement. No commercial use or repackaging of this
> dataset is allowed, in any form. Redistribution of CHGIS datasets in electronic media format, or by
> downloadable distribution over the Internet is not allowed, except under written agreement with
> CHGIS Management Committee.**"

> "(7) agreement: **Your download and/or use of the CHGIS Data under any circumstances implies your
> acceptance of this License and all of its conditions.**"

### 1-4. ⚠️ Harvard Dataverse의 상충 표기 (CC0 1.0)
실제 데이터 데이터셋 랜딩 페이지의 "Dataset Terms / License/Data Use Agreement" 표기:

- `doi:10.7910/DVN/ST5KKM` (V6 "1820 Layers UTF8 Encoding") → **"CC0 1.0"**
- `doi:10.7910/DVN/M7WEFY` (V5 Shapefiles, 2012) → **"CC0 1.0"**

DataCite 메타데이터도 동일:
`{"rights": "Creative Commons Zero v1.0 Universal", "rightsIdentifier": "cc0-1.0"}` (M7WEFY).
CC0로 표기된 DOI: V2 ZZKZ6U, V3 HIMIVE, V4 PDGOZ0, V5 M7WEFY·WEJMB6·E1FHML,
V6 ST5KKM·HHVVHX·2K4FHX·0P89R9·6CHSR7, Tibetan Monasteries W6PFXR.
"Custom terms": V6 EULA FDLFJ3, V3 Relational Database JX4KSQ.

이 CC0 표기가 **의도적 권리 부여인지 Dataverse 저장소 기본값인지 = UNKNOWN.**
`support.dataverse.harvard.edu`는 HTTP 403으로 확인 불가. 같은 데이터셋 안에 금지 조항 EULA가
동봉돼 있으므로 CC0 표기 단독으로 번들을 정당화하지 않는다.

### 1-5. TGAZ (Temporal Gazetteer)
- `https://github.com/cga-harvard/tgaz` → "TGaz is licensed under the GNU General Public License v3.0."
  — **소프트웨어/스키마** 라이선스이며 데이터 라이선스가 아니다.
- `https://tgaz.fudan.edu.cn/tgaz/indexEngVer.html` → "© CHGIS 2001 -"만, 약관 없음.
- **TGAZ 데이터 라이선스 = UNKNOWN.** 지명 콘텐츠가 CHGIS 유래이므로 CHGIS EULA가 적용될 개연성이
  있으나 미확인.

### 1-6. 제3자 재진술 (참고용, 권위 없음)
- `https://whgazetteer.org/datasets/1203/places` → "available under the … CC BY 4.0 License"
  — WHG **플랫폼 공통 문구**이지 CHGIS의 권리 부여가 아니다.
- `https://mdl.library.utoronto.ca/collections/geospatial-data/china-historical-gis` →
  "Permission Required: None / Restrictions: Public / OPEN" — 제3자 도서관 카탈로그 메타데이터,
  CHGIS EULA와 모순, 권위 없음.
- Fudan "China Historical Geographic Information System"은 별개 데이터셋이 아니라 **같은 프로젝트**다
  (Fudan Center는 EULA §2의 인가 배포처).

## 2. 판정

| 용도 | 판정 | 근거 |
|---|---|---|
| 비상업 학술 연구·교육 목적 내부 사용 | ALLOWED | EULA §3 |
| **상업 게임 프로젝트의 내부 위치 검증** | **RESTRICTED** | EULA §3 — "commercial or revenue generating use … must first obtain a licensing agreement". 상업 제품의 개발 파이프라인은 academic research가 아니다 |
| **제품에 번들** | **RESTRICTED (사실상 금지)** | EULA §5 — "may not incorporate the entirety of CHGIS Data Layers in a work of your own intended for public dissemination, whether modified or not"; "No commercial use or repackaging … in any form" |
| 재배포(다운로드·전자매체) | **RESTRICTED (금지)** | EULA §5 — 서면 계약 없이는 불가 |
| 파생물 | 부분 ALLOWED / 전체 RESTRICTED | §4는 "portions" + 인용·변경 명시 조건부 허용, §5는 전체 레이어 공개 배포 차단 |
| Dataverse "CC0 1.0" 표기에 근거한 번들 | **UNKNOWN** | 동봉 EULA와 직접 충돌, 표기 출처 미검증 |
| TGAZ 데이터 | **UNKNOWN** | 소프트웨어 GPLv3만 확인, 데이터 약관 미발견 |

버전별 차이: 한 줄 요약 문구는 v1~v6 동일. **전문 EULA는 V6(2016)만 확보**했고 v1~v5의 세부 조항
문구는 **UNKNOWN**. V3 Relational Database(JX4KSQ)는 별도 "Custom terms"이며 미확인 = **UNKNOWN**.

## 3. 코드 계약과의 연결

`logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt`의
`SourceLicense.bundling: LicenseBundling`에 기록한다.

- CHGIS v1~v6 → `LicenseBundling.UNKNOWN`.
  `RESEARCH_ONLY`로 적지 **않는다**: 그 값의 뜻은 "위치 검증 등 내부 연구에는 쓸 수 있다"인데
  §2 판정표는 상업 프로젝트의 내부 위치 검증조차 RESTRICTED로 결론냈다. 라벨이 실제보다 넉넉한 허가를
  암시하면 나중에 읽는 사람이 내부 사용을 승인된 것으로 오독한다. 비상업 학술 용도로만 쓰는 별도 맥락이
  생기면 그때 다시 판정한다.
- TGAZ 데이터 → `LicenseBundling.UNKNOWN`
- Dataverse CC0 표기 → 근거로 사용 금지

`EvidenceContractValidator.validateBundling()`은 `BUNDLING_ALLOWED`가 아닌 모든 근거를 차단한다.
`UNKNOWN`은 "아마 괜찮다"가 아니라 차단 사유다(테스트
`번들 미허가 라이선스는 제품 자산 검증에서 차단된다`).

## 4. 접근 제약 (재검증하는 사람 주의)

- `dataverse.harvard.edu`가 배너 고지: "access to our APIs, other than within a browser, is limited or
  disabled". curl/WebFetch는 HTTP 202 + AWS WAF JS 챌린지를 받는다. **브라우저로만** 재검증 가능
  (본 검토는 Playwright로 취득).
- `https://gis.harvard.edu/china-historical-gis` → HTTP 403, 미확인.
- `https://support.dataverse.harvard.edu/harvard-dataverse-general-terms-use` → HTTP 403, 미확인.

## 5. 권고

번들하지 않는다. 지도 데이터가 필요하면 (a) CHGIS Management Committee(Fairbank Center / Fudan)와
서면 계약을 맺거나 (b) 허용적 라이선스의 대체 사료 지리 데이터를 별도 조달한다. 후자는 본 티켓 범위 밖.
