# RTK14 지도 원본 헥스 지형 데이터화 독립 적대적 리뷰

- **reviewer:** `lane-map-datafy-b` (재배정 — 독립 검증자, `lane-map-datafy` 구현자와 분리; 구현 무편집)
- **review date:** 2026-07-17
- **artifacts under review (SHA-256):**
  - `tools/rtk14/build_rtk14_hexmap.py` — `a9f16a9250cc28c35cc1c91fb92ed3c9537f357405b041a4daea756c8aa1bb8e`
  - `tools/rtk14/test_build_rtk14_hexmap.py` — `c1f679aaf230e41be98ee70b353584eb6a00321190b01bd7a2f2196b2a93d9c9`
  - `docs/superpowers/research/2026-07-17-rtk14-hexmap-datafication.md` — `65c8f9278c785454b22bf2c92f57d7a073bde43a296cca8dc3969a6a1e915eed`
- **review basis:** 계약 §13-5 (`docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md`) + team-lead 지정 기준 (a)–(h) + 구현자 구체 주장(위키 근거·교정·버그 수정·통계·이중 실행·테스트) 재현/반증. 사용자 팔로업 지시("각 지도 이미지의 셀 색상은 위키에서 확인"). 교차 근거: OPENSAM-102 좌표 원장(동일 4181×4191 native-pixel 공간), 캐시된 wiki `地理` HTML(`chiri.html`).
- **재현 아티팩트(직접 실행):** 원본 `PK.png` sha256 `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89` (4181×4191, repo 밖 격리). 이중 실행 출력 JSON sha256 `0b18b00c9f82f1c5a1b706d3e92e3461196ab2c6110f903c76c189c4e9e5a9d9` (byte-identical).
- **FINAL VERDICT:** `cleared` (fix-required 0, note 2)

---

## 1. 리뷰 범위·독립성

구현자 보고를 신뢰하지 않고 결정성·테스트·시각 QA를 **직접 재실행**하고, 위키 그라운딩·회귀 3건·fail-closed를 산출물에서 수치로 재검했다. 세 소유 파일은 읽기만 했고 편집 0. 구현은 `lane-map-datafy`가 단독 writer로 마무리했으며, 본 리뷰어는 구현자가 아니므로 독립성이 성립한다.

## 2. 결정성·테스트 재실행 (기준 d·e — 직접)

| 검사 | 관측 | 판정 |
|---|---|---|
| 이중 실행 byte-identical | `verify_A.json` == `verify_B.json`, 양쪽 sha256 `0b18b00c…` | ✅ |
| 출력 셀 수 | 48,620 | ✅ |
| 테스트 스위트(repo root) | `Ran 29 tests OK` (신규 21 hexmap + 기존 8 stats 무회귀) | ✅ |
| 타임스탬프/비결정 요소 | 엔트리에 timestamp 없음, `cells` (q,r) 정렬 고정 | ✅ |

stats 스위트의 이전 FileNotFound는 cwd 아티팩트(상대 경로)일 뿐 — repo root 실행 시 8/8 green, 본 작업과 무관.

## 3. 원본 sha·헤더 무결성 (기준 a)

- `source.sha256` = `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89`, `width×height` = 4181×4191 — OPENSAM-102 원장과 정확히 일치. ✅
- `schema` = `rtk14-hexmap/v2`; `geometry`에 layout=offset-column, hex_size=19.0, origin_x=9.0, origin_y=0.0, odd_col_dy=9.5, sample_radius=4, color_tol=60, min_conf=0.5 기록. ✅
- `wiki_color_legend.source_url` = `https://wikiwiki.jp/sangokushi14/地理`; legend 22 entries; palette 23(=wiki 22 + sentinel OUT_OF_BOUNDS). ✅
- `cx,cy`는 원장과 동일 native-pixel 공간(洛陽 target (1890,1415.5) → 셀 중심 (1890.0,1415.5) 정확 일치). ✅

## 4. 위키 범례 grounding 재파싱 (기준 b — 눈대중 금지)

캐시 `chiri.html`에서 `background-color:#RRGGBB`를 독립 재파싱해 빌더 `WIKI_TERRAIN` 22 hex와 대조:

| 검사 | 관측 | 판정 |
|---|---|---|
| HTML 내 distinct background-color hex | 23종 | — |
| 빌더 legend 22 hex의 verbatim 존재 | **22/22** HTML에 존재 | ✅ |
| sentinel #f8f8f8(지도밖) HTML 존재 여부 | 부재 → wiki 지형 아님이 옳음 | ✅ |
| sentinel #bcbcbc(격자선) HTML 존재 여부 | 부재 → wiki 지형 아님이 옳음 | ✅ |

22개 지형 색이 모두 wiki HTML의 CSS 스와치와 byte-exact이므로 팔레트는 눈대중/하드코딩-추측이 아니라 **wiki 범례에 그라운딩**됐다. 코드 주석·JSON 헤더가 출처 URL을 명시해 검증 가능. 사용자 지시("셀 색상은 위키에서 확인") 충족.

## 5. 미매핑 색 UNKNOWN + observed_rgb (기준 c — 발명 금지)

- UNKNOWN 637셀 **전부** `observed_rgb` 보유; 비-UNKNOWN 셀은 `observed_rgb`를 절대 갖지 않음. ✅
- 샘플 UNKNOWN: `{q:0,r:17,cx:9.0,cy:323.0,confidence:0.0,observed_rgb:[13,13,13]}` — 근-흑색 지도 경계 outline(wiki 색과 tol 밖). 지형 발명 없이 관측 RGB만 기록. ✅

## 6. 회귀 3건 수치 재검 (기준 g — 산출 JSON에서 확인)

| 원 결함 | 기대(수정 후) | 산출 JSON 관측 | 판정 |
|---|---|---|---|
| ① 山道 3.58M px가 "GRID"로 폐기 | MOUNTAIN_PATH > 0 | **745** (1.5%) 복원 | ✅ 고쳐짐 |
| ② 大河 → "SEA" 오라벨 | MAJOR_RIVER 존재, SEA 코드 부재 | MAJOR_RIVER **4431**; `"SEA"` 토큰이 파일 전체(counts·palette·cells)에 **0회** | ✅ 고쳐짐 |
| ③ 府 폐기(점 마커 UNKNOWN) | GOVERNMENT > 0 | **242** (0.5%) 분류 | ✅ 고쳐짐 |

sentinel 제거 효과도 확인: PLAINS 4,847(10.0%)로 복원, OUT_OF_BOUNDS 7,674(15.8%) — 구현자 주장(平地 복원, 25.8%→15.8%)과 정합.

## 7. 5지역 시각 QA (기준 f — 직접 판정, 원장 좌표 반경 220px 히스토그램)

| 지역 (center) | 상위 지형 (n) | 판정 |
|---|---|---|
| 洛陽 (1890,1415.5) | PLAINS 150, FOREST 52, MOUNTAIN_LOW 37, STEEP_MOUNTAIN 34, ROAD 29 | ✅ 중원 평지+도로 |
| 成都 (219,2745.5) | OUT_OF_BOUNDS 98, PLAINS 96, FOREST 43, MOUNTAIN_MID 31, ROAD 31 | ✅ 분지 서변(지도밖 경계+평지) |
| 建業 (3638,2345.5) | MAJOR_RIVER 142, RAPIDS 77, PLAINS 42, MOUNTAIN_LOW 26 | ✅ 양자강 삼각주 |
| 南蛮 (997,4123.5) | DENSE_FOREST 75, STEEP_MOUNTAIN 46, UNKNOWN 46, POISON_SWAMP 17 | ✅ 밀림 시그니처 |
| 武威 (124,570.5) | SAND 224, OUT_OF_BOUNDS 55, UNKNOWN 41, ROAD 20, CITY 7 | ✅ 서북 사막 |

전 지역 지리적으로 정합. 南蛮/武威의 UNKNOWN은 근-흑색 남·서 경계 outline(§8 문서화 한계)로, 지형 아님이 올바른 결과.

## 8. fail-closed 실사격 + 추적 바이너리 0 (기준 h)

| 검사 | 관측 | 판정 |
|---|---|---|
| tracked `--out` 경로 | `SystemExit(fail-closed)`, 파일 **미생성** | ✅ |
| tracked `--source` 경로 | `SystemExit(fail-closed)`, 파일 **미생성** | ✅ |
| 추적된 이미지/산출 바이너리 | `git ls-files`에 PK.png·hexmap json/png **0** | ✅ (RIGHTS WARN 준수) |
| 소유 3파일만 untracked | build/test/research doc만 `??`, 그 외 없음 | ✅ |
| `build_rtk14_stats.py`/test 불가침 | git status 무변경(무편집) | ✅ |

## 9. Findings

### N1 — `note` (PORT/SHALLOWS = 0, 문서화된 한계)
`港`/`浅瀬`는 팔레트에 wiki 색으로 존재하나 산출 셀 0. 원인은 결함이 아니라 해상도 한계 — 항구·여울은 해안의 소형 지물이라 9×9 셀 중심 윈도에서 majority를 얻지 못한다. 역할 분리로 정당화됨: OPENSAM-102가 55개 소형 거점(港/関所 포함)을 **정확한 fill-component 좌표**로 이미 소싱하므로 헥스 라벨의 누락이 데이터 공백을 만들지 않는다. 연구 문서 §8이 정직하게 기록(L113). 비-blocking.

### N2 — `note` (tol=60 L1 경계 근접 — 平地)
`color_tol=60`(L1)은 원칙 있는 경계이나 `平地`가 스와치보다 밝게 렌더돼 L1≈59로 tol 경계에 **근접**한다(§8 L112 명기). 현재 입력에서는 안전하지만, 다른 리비전/압축의 지도 이미지에서 平地가 tol 밖으로 밀리면 PLAINS가 UNKNOWN으로 새어나갈 수 있다. 문서가 이 취약성을 정직하게 기록하므로 비-blocking. 다운스트림 권고: 새 소스 이미지 사용 시 平地 L1 분포를 재측정.

## 10. 결론

기준 (a)–(h)와 구현자의 구체 주장을 **직접 재현으로 통과**시켰다: 원본 sha·기하 일치, 팔레트 22/22 hex가 wiki 범례에 byte-exact 그라운딩(눈대중 아님), 미매핑 색은 UNKNOWN+observed_rgb로 발명 없이 보존, 이중 실행 byte-identical(SHA `0b18b00c…`), 신규 21 + 기존 stats 8 = 29 테스트 green, fail-closed 양방향 실사격 + 추적 바이너리 0, 5지역 히스토그램 전부 지리 정합. 원 결함 3건(山道 폐기·大河→SEA·府 폐기)은 산출 JSON에서 수치로 실제 수정 확인(MOUNTAIN_PATH 745 / SEA 0회 / GOVERNMENT 242).

잔여 2건은 모두 non-blocking note(PORT·SHALLOWS 해상도 한계는 OPENSAM-102 소거점 좌표로 역할 분리, 平地 tol 경계 근접은 문서에 정직 기록)이며 §13-5 요건이나 데이터 무결성을 위반하지 않는다.

**FINAL VERDICT: `cleared`** (fix-required 0 / note 2: N1 PORT·SHALLOWS=0 문서화 한계, N2 平地 tol 경계 근접). clearance는 산출물 품질 판정일 뿐 — 이 헥스맵의 runtime 삽입은 OPENSAM-102/103 좌표 versioning·권리 clearance 통과 후에만 가능하며, 원본 이미지·산출 JSON은 코에이 IP로 계속 repo 밖 격리·미커밋한다.
