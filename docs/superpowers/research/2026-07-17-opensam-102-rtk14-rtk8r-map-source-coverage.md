# OPENSAM-102 RTK14·RTK8R 지도 소스 커버리지

- **status:** `DONE (A2 independent review cleared 2026-07-17; SPEC/DATA/IMAGE PASS; RIGHTS WARN)`
- **scope:** research-only native-pixel extraction; runtime scraper/builder/asset bundle 없음
- **access date:** 2026-07-17
- **source classes:** RTK 자료는 모두 `GAME_REFERENCE`; 역사 증거로 사용하지 않음
- **downstream:** `OPENSAM-102 → OPENSAM-103 → OPENSAM-105`; 이 문서는 후속 구현을 승인하지 않음

## 1. 결론

1. `[사실][DIRECT]` RTK14 WIKIWIKI의 `都市` 페이지는 **46도시·338지역**을 명시하고, bounded 재확인에서 도시명 46행이 모두 accessible text로 노출됐다. 아래 row ledger가 source order `001..046`, page line/link locator, composite fingerprint를 고정한다. 이는 source-row coverage이며 opensamguk stable ID·alias reconciliation 또는 338개 지역 행 완료를 뜻하지 않는다.
2. `[사실][DIRECT]` RTK8R 공식 웹 매뉴얼은 도시·지도·`region → province → city` 계층을 한 페이지에서 제공한다. `[추론][DERIVED]` 표의 행을 지역별로 합산하면 **51도시**이고, 지역은 직접 열거된 **6개**, province 그룹은 **17개**다. 51이라는 총계는 페이지가 명시한 숫자가 아니라 `4+9+8+11+10+9`의 결정적 행 합계다.
3. `[사실][MANUAL]` 두 게임 모두 개별 community city page에 `隣接都市` 형식이 존재하는 표본은 확인됐다. 그러나 RTK14 46페이지와 RTK8R 51페이지를 전수 감사하지 않았다. 따라서 전체 adjacency, 방향성, 대칭성, edge count는 모두 `[UNKNOWN]`이며 reverse edge를 자동 생성하지 않는다.
4. `[사실][DIRECT+DERIVED+MANUAL]` 텍스트/표에는 수치 좌표가 없지만, 사용자 승인으로 지정된 원본 `PK.png`를 repo 밖 격리 source artifact로 보존해 native `4181×4191` pixels에서 46 city와 55 small-base marker의 plaque center를 추출했다. 좌표는 이미지 presentation geometry일 뿐 world coordinate·projection·역사 위치가 아니다.
5. `[사실]` WIKIWIKI와 Koei Tecmo 공식 매뉴얼 어디에서도 opensamguk에 대한 공개 재배포 허락을 확인하지 못했다. 텍스트·표·이미지는 **research metadata only**이며 repo bundle/runtime allowlist로 승격하지 않는다.

## 2. 판정 규칙과 조사 경계

| 분류 | 이 문서에서의 의미 |
|---|---|
| `DIRECT` | source section의 텍스트나 표가 해당 필드를 직접 제공한다. source의 정확성·역사성·재배포 권리까지 뜻하지 않는다. |
| `DERIVED` | `DIRECT` 행의 개수 합산이나 이름→stable-ID join처럼 재현 가능한 변환이다. 입력과 알고리즘을 함께 기록하며 geometry를 만들지 않는다. |
| `MANUAL` | source에는 후보 표현이 있으나 페이지별 전사·alias reconciliation·2인 검토가 필요하다. 현재 완성 데이터를 뜻하지 않는다. |
| `UNAVAILABLE` | 조사 범위의 허용된 텍스트/표에는 필드가 없다. 이미지 추정이나 대칭 edge 날조로 채우지 않는다. |

- RTK14 direct-page reconnaissance는 `/tmp/opensam-wiki-source-brief.md`의 검증된 SHA-256 `5330f2d494d39c112405797f6e980c2801d9644bba29a06e41bef87480d83f08`을 입력으로 사용했다. 해당 pass의 접근 창은 `2026-07-17T02:42:10Z–02:48:11Z`였다.
- Row-level review fix는 `R14-W-CITY` 한 URL만 `2026-07-17T03:16:59Z–03:17:37Z`에 직렬로 다시 열었다. final URL은 요청 URL과 같았고 fetcher snapshot은 255 lines였다. 다른 RTK14 페이지나 attachment는 요청하지 않았다.
- RTK8R/공식 매뉴얼 확인은 직렬 read-only 요청으로 수행했다. fetcher가 개별 request 시각을 노출하지 않아 access date와 종료 marker `2026-07-17T02:59:00Z`만 기록한다. 이 제한을 보완하려고 page-displayed modification 값과 fetcher line count를 fingerprint로 남긴다.
- raw HTML/MHTML은 archive하지 않았다. 승인된 `PK.png` attachment는 fresh `/tmp/opensam-102-DpUFJ6/`에 취득한 뒤 exact bytes를 repo 밖 `/Users/apple/.codex/visualizations/2026/07/17/019f6da9-8684-7500-a561-477b7aea3e48/opensam-102/source/PK.png`에 terrain/coordinate research용 격리 source로 보존했다. 같은 `source/manifest.md`가 fingerprint와 권리 경계를 기록하며 Git/runtime 사용은 금지한다.
- WIKIWIKI command-line 접근은 선행 brief에서 Cloudflare HTTP 403을 반환했다. challenge 우회·재시도·sitemap crawl을 하지 않았다. browser-like read-only fetcher만 사용했다.

## 3. Source/provenance ledger

| ID | provenance와 exact section | requested → final URL | access / fingerprint / archive | 확인한 범위 | license·extractability |
|---|---|---|---|---|---|
| `R14-W-CITY` | community WIKIWIKI, `都市`의 `都市データ` 표 | [都市](https://wikiwiki.jp/sangokushi14/%E9%83%BD%E5%B8%82) → same | initial `2026-07-17T02:42:10Z–02:48:11Z`; row pass `03:16:59Z–03:17:37Z`; displayed modified `2026-06-19 19:10:12`; fetch 255 lines; explicit `46/338`; archive `NONE` | 46 city identities 직접 관측, 338지역 count, 대지역·주·도시와 경제/영토 열 | 이 문서의 최소 factual-name ledger만 허용. 공개 재배포 grant는 여전히 미확인이라 full table/338 rows/assets extraction은 `BLOCKED/UNKNOWN`. |
| `R14-W-GEO` | community WIKIWIKI, `地理`의 PK 도시 배치·지도 절과 그 46 city/5 ethnic links | [地理](https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E7%90%86) → same | displayed modified `2026-07-05 19:07:17`; original [PK.png](https://cdn.wikiwiki.jp/to/w/sangokushi14/%E5%9C%B0%E7%90%86/%3A%3Aattach/PK.png?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804); SHA-256 `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89`; `4181×4191`; RGB PNG; `281922` bytes; non-repo quarantined source | 46 city plaques, 40 port plaques, 10 gate plaques, 5 ethnic-stronghold plaques; linked detail rows give name/kind/parent | authorship/reuse remains `UNKNOWN`; terrain/coordinate research only. Original is not bundled/hotlinked; source and derivative stay outside repo. |
| `R14-W-REGION` | community WIKIWIKI, `地域収入`의 city total/region rows | [地域収入](https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E5%9F%9F%E5%8F%8E%E5%85%A5) → same | same access window; displayed modified `2026-06-19 20:18:07`; archive `NONE` | region type/name/reading/parent city/gold/food/soldiers/territory/construction cap | 구조화 표지만 bulk copy·redistribution clearance 없음. |
| `R14-W-LUOYANG` | community WIKIWIKI, `洛陽`의 `隣接都市` | [洛陽](https://wikiwiki.jp/sangokushi14/%E6%B4%9B%E9%99%BD) → same | same access window; displayed modified `2026-03-15 13:22:19`; archive `NONE` | 표본 claimed neighbors `陳留・長安・晋陽`; 같은 페이지 prose가 `晋陽` adjacency/AI 해석에 의문을 제기 | 한 표본은 전체 graph 증거가 아님. edge claim과 editorial conflict를 함께 보존해야 함. |
| `R14-O-MANUAL` | Koei Tecmo official manual, `Land Structure / City Region` | [Game Overview](https://www.koeitecmoamerica.com/manual/rtk14/en/3100.html) → same | access date `2026-07-17`; fetch snapshot 105 lines; copyright footer `©2020 ... All rights reserved`; archive `NONE` | HEX→area→city region, city/gate/port가 core라는 공식 게임 의미 | 공식 `GAME_REFERENCE`; open-data/redistribution grant 없음. 46/338 count source는 아님. |
| `R8-O-MAP` | Koei Tecmo official manual, `Full Map / Regional Map / List of Regions, Provinces, and Cities` | [Full Map/Regional Map](https://www.koeitecmoamerica.com/manual/rtk8-remake/en/6100.html) → same | access date `2026-07-17`, end marker `02:59:00Z`; fetch snapshot 295 lines; server validator/byte hash 없음; archive `NONE` | full/regional map image references, 6 regions, 17 province labels, 51 city rows by deterministic count | publisher source지만 public reuse license 미확인. Text discovery only; image reuse/extraction blocked. |
| `R8-W-CITY` | community WIKIWIKI, `都市一覧`의 `概要 / 全体地図 / 都市一覧` | [都市一覧](https://wikiwiki.jp/sangokushi8r/%E9%83%BD%E5%B8%82%E4%B8%80%E8%A6%A7) → same | access date `2026-07-17`; displayed modified `2026-02-18 17:52:08`; fetch snapshot 222 lines; archive `NONE` | official page와 같은 6-region hierarchy, city type, overall map image; 51 city rows by count | secondary cross-check. WIKIWIKI public redistribution grant 미확인. |
| `R8-W-WU` | community WIKIWIKI, `呉 / 基本情報 / 隣接都市` | [呉](https://wikiwiki.jp/sangokushi8r/%E5%91%89) → same | access date `2026-07-17`; displayed modified `2025-02-13 00:40:02`; fetch snapshot 152 lines; archive `NONE` | claimed adjacency `建業`, `会稽`; route-specific battle image references | adjacency schema 표본일 뿐 전체 coverage 아님. |
| `R8-W-JIANYE` | community WIKIWIKI, `建業 / 基本情報 / 隣接都市` | [建業](https://wikiwiki.jp/sangokushi8r/%E5%BB%BA%E6%A5%AD) → same | access date `2026-07-17`; displayed modified `2025-02-13 01:46:58`; fetch snapshot 159 lines; archive `NONE` | adjacency에 `呉`가 있어 표본 1쌍의 reverse claim 확인; 213년부터 `秣陵→建業` rename 설명 | 표본 reverse claim을 전 graph 대칭성으로 일반화 금지. display name 단독 key도 금지. |
| `LEGAL-WIKI` | site-wide WIKIWIKI policies/rules/robots | [policies](https://wikiwiki.jp/pp/policies), [rules](https://wikiwiki.jp/pp/rules), [robots.txt](https://wikiwiki.jp/robots.txt) | brief access `2026-07-17T02:47Z`; robots text fingerprint `Disallow: /*?`, `Disallow: /*/::*`, `Allow: /common/`; archive `NONE` | third-party IP/load/hotlink restrictions; public downstream grant 미발견 | robots 준수는 license가 아님. written clearance 전 structured ingestion과 asset reuse 차단. |

사이트가 표시한 modification timezone은 명시되지 않았다. RTK8R official manual snapshot에는 displayed modification/ETag/Last-Modified가 노출되지 않아 URL, access marker, section title, 295-line snapshot, count invariant를 복합 fingerprint로 사용했다.

## 4. RTK14 coverage matrix

| 데이터 차원 | 판정 | 관측 coverage | 근거와 extractability | 현재 결정 |
|---|---|---|---|---|
| city list | `DIRECT` | source count claim `46/46`; row evidence `46/46`; raw archive `NONE` | `R14-W-CITY` lines 68, 70-137과 links 15-60. 아래 ledger는 이름만 최소 전사했다. | source identity coverage 충족. opensamguk stable ID, alias, cross-version join은 `[UNKNOWN]`. |
| adjacency | `MANUAL` | 표본 `1/46` city page; verified complete graph `0/46` | `R14-W-LUOYANG`에 3개 claimed neighbor가 있으나 `晋陽` 관련 prose conflict 존재. 전체 city-page audit 없음. | directed claim으로만 보존. 대칭 edge·route type·edge count는 `[UNKNOWN]`. |
| native pixel coordinate | `DIRECT+DERIVED+MANUAL` | city `46/46`; small base `55/55 observed` | exact `PK.png` fingerprint/dimensions, color/shape component bbox, manual label adjudication, 46 linked city pages and 5 ethnic pages. | [coordinate ledger](./2026-07-17-opensam-102-map-coordinate-ledger.csv) records inclusive plaque-fill bbox, center, normalized value, method/confidence/review. World/projection meaning remains `UNKNOWN`. |
| map image | `DIRECT` | PK original 1 quarantined source; annotated derivative 1 outside repo | original SHA-256/dimensions/bytes above; derivative SHA-256 `8a70167f07be23362ab0ea4879a4cf6ba53e84c2deedb2815269e36d171f1d18`. | Original bundle/render/hotlink prohibited; source is terrain-analysis evidence and derivative is review evidence, not runtime assets. |
| region mapping | `DIRECT` | explicit total `338`; row archive `0/338` | `R14-W-REGION`은 각 region의 parent city field를 제공하고 `R14-W-CITY`가 338을 명시. | schema/count-level coverage. 338 row identity·부모 무결성·도시별 합계 reconciliation은 `[UNKNOWN]`. |

### RTK14 46-row direct-evidence ledger

Fingerprint 형식은 `R14CITY:m20260619-191012:a<link-id>:l<fetch-line>`이다. 이는 source byte hash가 아니라 **canonical page + displayed modified + link id + fetch line**의 composite locator다. Source가 바뀌면 다시 확인해야 하며 raw page archive를 대신하지 않는다.

| ordinal | city name | source locator | coverage | row fingerprint |
|---:|---|---|---|---|
| 001 | 襄平 | `都市データ L72 / link 15` | `DIRECT` | `R14CITY:m20260619-191012:a15:l72` |
| 002 | 北平 | `都市データ L73 / link 16` | `DIRECT` | `R14CITY:m20260619-191012:a16:l73` |
| 003 | 薊 | `都市データ L74 / link 17` | `DIRECT` | `R14CITY:m20260619-191012:a17:l74` |
| 004 | 晋陽 | `都市データ L76 / link 18` | `DIRECT` | `R14CITY:m20260619-191012:a18:l76` |
| 005 | 南皮 | `都市データ L79 / link 19` | `DIRECT` | `R14CITY:m20260619-191012:a19:l79` |
| 006 | 鄴 | `都市データ L80 / link 20` | `DIRECT` | `R14CITY:m20260619-191012:a20:l80` |
| 007 | 平原 | `都市データ L83 / link 21` | `DIRECT` | `R14CITY:m20260619-191012:a21:l83` |
| 008 | 北海 | `都市データ L84 / link 22` | `DIRECT` | `R14CITY:m20260619-191012:a22:l84` |
| 009 | 下邳 | `都市データ L87 / link 23` | `DIRECT` | `R14CITY:m20260619-191012:a23:l87` |
| 010 | 小沛 | `都市データ L88 / link 24` | `DIRECT` | `R14CITY:m20260619-191012:a24:l88` |
| 011 | 広陵 | `都市データ L89 / link 25` | `DIRECT` | `R14CITY:m20260619-191012:a25:l89` |
| 012 | 寿春 | `都市データ L91 / link 26` | `DIRECT` | `R14CITY:m20260619-191012:a26:l91` |
| 013 | 廬江 | `都市データ L92 / link 27` | `DIRECT` | `R14CITY:m20260619-191012:a27:l92` |
| 014 | 濮陽 | `都市データ L94 / link 28` | `DIRECT` | `R14CITY:m20260619-191012:a28:l94` |
| 015 | 陳留 | `都市データ L95 / link 29` | `DIRECT` | `R14CITY:m20260619-191012:a29:l95` |
| 016 | 許昌 | `都市データ L97 / link 30` | `DIRECT` | `R14CITY:m20260619-191012:a30:l97` |
| 017 | 汝南 | `都市データ L98 / link 31` | `DIRECT` | `R14CITY:m20260619-191012:a31:l98` |
| 018 | 洛陽 | `都市データ L100 / link 32` | `DIRECT` | `R14CITY:m20260619-191012:a32:l100` |
| 019 | 宛 | `都市データ L102 / link 33` | `DIRECT` | `R14CITY:m20260619-191012:a33:l102` |
| 020 | 長安 | `都市データ L103 / link 34` | `DIRECT` | `R14CITY:m20260619-191012:a34:l103` |
| 021 | 上庸 | `都市データ L104 / link 35` | `DIRECT` | `R14CITY:m20260619-191012:a35:l104` |
| 022 | 安定 | `都市データ L106 / link 36` | `DIRECT` | `R14CITY:m20260619-191012:a36:l106` |
| 023 | 天水 | `都市データ L107 / link 37` | `DIRECT` | `R14CITY:m20260619-191012:a37:l107` |
| 024 | 武威 | `都市データ L108 / link 38` | `DIRECT` | `R14CITY:m20260619-191012:a38:l108` |
| 025 | 建業 | `都市データ L110 / link 39` | `DIRECT` | `R14CITY:m20260619-191012:a39:l110` |
| 026 | 呉 | `都市データ L111 / link 40` | `DIRECT` | `R14CITY:m20260619-191012:a40:l111` |
| 027 | 会稽 | `都市データ L112 / link 41` | `DIRECT` | `R14CITY:m20260619-191012:a41:l112` |
| 028 | 柴桑 | `都市データ L113 / link 42` | `DIRECT` | `R14CITY:m20260619-191012:a42:l113` |
| 029 | 建安 | `都市データ L114 / link 43` | `DIRECT` | `R14CITY:m20260619-191012:a43:l114` |
| 030 | 南海 | `都市データ L116 / link 44` | `DIRECT` | `R14CITY:m20260619-191012:a44:l116` |
| 031 | 交趾 | `都市データ L117 / link 45` | `DIRECT` | `R14CITY:m20260619-191012:a45:l117` |
| 032 | 江夏 | `都市データ L120 / link 46` | `DIRECT` | `R14CITY:m20260619-191012:a46:l120` |
| 033 | 新野 | `都市データ L121 / link 47` | `DIRECT` | `R14CITY:m20260619-191012:a47:l121` |
| 034 | 襄陽 | `都市データ L122 / link 48` | `DIRECT` | `R14CITY:m20260619-191012:a48:l122` |
| 035 | 江陵 | `都市データ L123 / link 49` | `DIRECT` | `R14CITY:m20260619-191012:a49:l123` |
| 036 | 長沙 | `都市データ L125 / link 50` | `DIRECT` | `R14CITY:m20260619-191012:a50:l125` |
| 037 | 武陵 | `都市データ L126 / link 51` | `DIRECT` | `R14CITY:m20260619-191012:a51:l126` |
| 038 | 桂陽 | `都市データ L127 / link 52` | `DIRECT` | `R14CITY:m20260619-191012:a52:l127` |
| 039 | 零陵 | `都市データ L128 / link 53` | `DIRECT` | `R14CITY:m20260619-191012:a53:l128` |
| 040 | 永安 | `都市データ L130 / link 54` | `DIRECT` | `R14CITY:m20260619-191012:a54:l130` |
| 041 | 漢中 | `都市データ L131 / link 55` | `DIRECT` | `R14CITY:m20260619-191012:a55:l131` |
| 042 | 梓潼 | `都市データ L132 / link 56` | `DIRECT` | `R14CITY:m20260619-191012:a56:l132` |
| 043 | 江州 | `都市データ L133 / link 57` | `DIRECT` | `R14CITY:m20260619-191012:a57:l133` |
| 044 | 成都 | `都市データ L134 / link 58` | `DIRECT` | `R14CITY:m20260619-191012:a58:l134` |
| 045 | 建寧 | `都市データ L136 / link 59` | `DIRECT` | `R14CITY:m20260619-191012:a59:l136` |
| 046 | 雲南 | `都市データ L137 / link 60` | `DIRECT` | `R14CITY:m20260619-191012:a60:l137` |

Ledger invariant: ordinal `001..046` contiguous, name 46개, fingerprint 46개, duplicate 0. 이름 이외의 수입·전법·수치 열은 복사하지 않았다.

### 46-row downstream acceptance envelope

`46`은 기대 행을 채우기 위한 생성 목표가 아니라 source의 직접 count claim이다. 위 ledger로 `46/46 source rows evidenced`는 충족됐지만, OPENSAM-103/105가 이 source를 선택하더라도 다음이 모두 통과하기 전 `46/46 imported`로 쓰면 안 된다.

- name/alias가 정확히 46개 opensamguk stable identity로 reconcile됨
- source ledger 중복·누락 0과 fingerprint 46개는 이 문서에서 통과; approved archive/raw-row hash는 후속 rights gate 대상
- 338 region 각각 parent city 1개, orphan 0
- city aggregate와 region aggregate가 source 내에서 일치
- license/redistribution가 A2에서 승인됨

## 5. RTK8R 5-dimension coverage matrix

| 데이터 차원 | 판정 | 관측 coverage | 근거와 extractability | 현재 결정 |
|---|---|---|---|---|
| city | `DIRECT` + count `DERIVED` | official city rows `51`; `4+9+8+11+10+9=51` | `R8-O-MAP`이 city hierarchy를 직접 열거하고 `R8-W-CITY`가 교차 확인. | 51은 reproducible row count지만 explicit publisher total은 아님. stable ID/alias는 `[UNKNOWN]`. |
| adjacency | `MANUAL` | 표본 2/51 city pages; 한 reverse claim pair만 관측 | official map page에는 textual adjacency table이 없고 community city pages에 `隣接都市`가 있음. | full graph·direction·symmetry·edge count `UNKNOWN`; 자동 reverse 생성 금지. |
| coordinate | `UNAVAILABLE` | `0/51` numeric rows | official/community map images는 있으나 텍스트 `x/y` 없음. | 모두 `UNKNOWN`; pixel/world coordinate 발명 금지. |
| map | `DIRECT` | official full map + 6 regional image references; community overall map 1 | page section과 image reference만 관측. binary/hash/license 없음. | discovery/visual cross-check만 가능. asset ingestion 불가. |
| region | `DIRECT` + province count `DERIVED` | 6 regions direct, 17 province groups derived, 51 city assignments | `R8-O-MAP`의 hierarchy. 17은 `2+2+3+5+2+3`. | 게임 UI grouping으로만 취급. 역사 행정 정본으로 승격 금지. |

`秣陵 (建業)`와 community page의 213년 rename은 display name이 영구 identity가 아님을 직접 보여준다. 후속 key contract는 time-scoped alias를 다뤄야 하며 이름 하나를 `MapJson.id`로 암묵 변환하면 안 된다.

## 6. MANUAL adjacency 절차와 2인 검토 gate

이 절은 현재 graph가 완성됐다는 뜻이 아니라 A2 이후 선택 가능한 절차다.

1. **권리 gate:** WIKIWIKI 관리자/권리자 또는 법무가 bounded factual transcription을 허용하기 전 시작하지 않는다.
2. **source freeze:** 게임/version, canonical URL, final URL, displayed modified, access UTC, archive ID 또는 approved raw-row hash를 page마다 기록한다.
3. **독립 전사:** Reviewer A/B가 각각 모든 city page의 표에 실제로 적힌 `fromName → toName` claim만 전사한다. 빈 칸, self-loop, 주석/prose conflict도 숨기지 않는다.
4. **identity join:** city master의 stable ID에 join한다. `秣陵/建業` 같은 alias는 scenario validity와 함께 처리하고 이름으로 임의 merge하지 않는다.
5. **reconcile:** A/B 결과가 byte-identical하지 않은 edge는 conflict queue로 보내며 다수결로 고치지 않는다.
6. **validation:** endpoint orphan 0, duplicate claim 0을 확인한다. reverse claim 비율과 비대칭 목록을 **보고만** 하고 symmetry를 repair하지 않는다.
7. **game validation:** 실제 게임 동작 또는 publisher source가 edge 의미를 확인하기 전 `RouteCorridor`로 승격하지 않는다. adjacency는 road geometry/type/capacity를 증명하지 않는다.

RTK14의 `洛陽–晋陽`은 이 절차가 conflict를 보존해야 하는 concrete sample이다. RTK8R의 `呉↔建業`은 표본 한 쌍의 양방향 claim일 뿐 전체 graph 불변식이 아니다.

## 7. v1 `MapJson` / `ScenarioJson` mapping

현재 v1 loader 근거는 `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt:7-31,44-55,65-96`과 `ScenarioJson.kt:172-225,284-299,367-393`이다.

| source datum | v1 target | mapping 판정과 손실 |
|---|---|---|
| city identity/name | `MapJson.cities[].id/name`, `ScenarioCity.id/name` | 이름은 `DIRECT`지만 numeric ID는 source에 없음. ID assignment는 OPENSAM-103/105 contract가 필요해 현재 `UNKNOWN`. |
| adjacency claim | `MapCityDetail.connections: List<Int>` | name endpoint를 stable ID로 join한 뒤에만 `DERIVED`. 현재 full graph가 `MANUAL/UNKNOWN`이라 작성 금지. directed claim을 무조건 symmetric list로 바꾸지 않는다. |
| coordinate | `MapCityCoord.x/y`, detail `x/y`, `ScenarioCity.x/y` | `PK.png` native presentation coordinates now exist as research evidence. Runtime use still requires OPENSAM-103/105 to define image version, scaling/crop contract, rounding (`loadMapCities` truncates Double), and asset rights; direct insertion is not approved. |
| map image/canvas | `MapData.width/height`; image field 없음 | observed canvas is `4181×4191`, but MapJson has no image provenance/license slot and the source asset cannot be bundled. A separately licensed/rebuilt presentation asset contract is still required. |
| RTK14 338 region 또는 RTK8R region/province | `MapCityDetail.region: Int`, `ScenarioCity.region: Int` | source는 이름 계층/parent city를 제공하지만 v1 `region` integer의 의미와 codebook 일치가 증명되지 않았다. 직접 cast 금지, 현재 `UNKNOWN`. |
| economics/city type | detail `max/initial`, `ScenarioCity.*Max/*Init` | wiki income/type을 v1 cap/init로 환산하는 공식이 없다. 이번 ticket 비범위이며 `UNAVAILABLE`. |
| scenario metadata | `Scenario.map: Map<String, Any?>` | untyped metadata container일 뿐 city geometry/edge contract가 아니다. source provenance를 임의 key로 넣는 것은 후속 schema 승인 전 금지. |

## 8. v2 `PhysicalPlace` / `RouteCorridor` / `EvidenceRef` mapping

v2 정본은 `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md`의 출처 계층과 지도 계약을 따른다.

| source datum | v2 mapping | 허용 경계 |
|---|---|---|
| RTK city row | `PhysicalPlace.names[]`의 discovery candidate | RTK 자료만으로 historical `placeIdentityKey`, valid time, exact place를 확정하지 않는다. 모든 claim은 `GAME_REFERENCE`; 별도 역사 근거가 필요하다. |
| game region/province hierarchy | `EvidenceRef`가 붙은 game-reference grouping | `TemporalAdministrativeUnit` 역사 정본으로 사용 금지. RTK14 338 area도 v2의 `PhysicalPlace` 2,000 product budget과 같은 count가 아니다. |
| city adjacency | future `RouteCorridor.endpoints` candidate | 양 endpoint와 실제 route 의미가 검증된 뒤에만 승격. `type`, geometry, capacity, grade, seasonality, direction은 전부 `UNKNOWN`. |
| native image coordinate | `ScenarioPlacement.playableAnchor` research candidate only | extracted pixels describe this exact game-reference image; they do not resolve `PhysicalPlace.locationResolution`, historical `CANDIDATE_REGION`, or simulation world coordinates. No v2 record is created in this ticket. |
| map image | presentation/asset manifest 후보 | simulation coordinate, `projectionVersion`, terrain truth가 아니다. license-cleared asset contract 전 bundle 금지. |
| source record | `EvidenceRef(title, passage/section, url, license, ...)` + `HistoricalClaim` | `sourceProximity=GAME`, `evidenceClass=GAME_REFERENCE`를 사용한다. `EvidenceRef.sourceType`와 license enum의 정확한 값은 아직 `[UNKNOWN]`; 새 enum을 발명하지 않는다. |

## 9. Source choice와 승인 gate

### GAME_REFERENCE와 history/evidence 트랙 분리

- **GAME_REFERENCE 트랙:** 이 문서의 RTK14/RTK8R wiki·official-manual 근거 전부. 게임 내 도시 집합, UI grouping, claimed adjacency, map presentation을 설명할 수 있지만 역사적 위치·행정·도로의 증거는 아니다.
- **history/evidence 트랙:** 1차 사료·고고·학술 복원의 별도 `EvidenceRef/HistoricalClaim`. 이번 bounded pass는 이 트랙을 조사하거나 채택하지 않았다. `PhysicalPlace.RESOLVED_POINT`, 역사 `TemporalAdministrativeUnit`, 실제 `RouteCorridor` 승격은 이 트랙의 별도 근거가 필요하다.
- 두 트랙은 같은 지명을 가질 수 있지만 claim과 confidence를 합치지 않는다. RTK14 native image coordinate 또는 RTK8R에서 비어 있는 coordinate를 history/world coordinate처럼 보간하거나, 게임 region을 역사 행정단위로 이름만 바꿔 복사하지 않는다.

### 권고 source choice

- **RTK14:** `R14-W-CITY + R14-W-REGION`을 city/area schema와 count discovery의 주 source로, `R14-O-MANUAL`을 game semantics 교차 확인으로 사용한다. adjacency는 개별 city page claim lane으로 분리한다.
- **RTK8R:** publisher `R8-O-MAP`을 city/region/map의 주 source로 사용하고 `R8-W-CITY`는 secondary cross-check로만 둔다. adjacency 후보는 community per-city page밖에 확인되지 않았다.
- 두 게임의 source를 합쳐 빈 coordinate/edge를 보간하지 않는다. RTK14와 RTK8R은 별도 versioned graph다.

### hard gates

| gate | 통과 조건 | 현재 |
|---|---|---|
| source choice | 위 primary/secondary 역할, version, field authority를 사람이 승인 | `A2 CLEARED 2026-07-17`: `SPEC PASS`; rights는 별도 WARN/BLOCKED 유지 |
| manual extraction | written rights clearance + 2인 독립 전사 + row/page fingerprint + conflict report | `BLOCKED` |
| license/bundling | text/table/image 각각 redistribution 근거 확인 | `BLOCKED`; research-only |
| coordinates | sanctioned image + top-left native-pixel system + fingerprint/dimensions + validation | `A2 CLEARED 2026-07-17`: `DATA/IMAGE PASS`, 101 unique centers; runtime adoption remains pending |
| adjacency | 모든 city page coverage + endpoint join + 비대칭 보고 + game validation | `MANUAL/UNKNOWN` |
| historical adoption | primary/scholarly evidence를 별도 `EvidenceRef/HistoricalClaim`로 연결 | `NOT STARTED`; GAME_REFERENCE만 있음 |

## 10. Downstream `102 → 103 → 105`

- **OPENSAM-102 (이 문서):** source availability, provenance, license, extractability, conflicts와 `UNKNOWN`을 고정하고, research-only coordinate ledger, repo 밖 quarantined original, annotated review derivative를 만든다. Runtime/repository asset은 만들지 않는다.
- **OPENSAM-103:** 사람이 source choice를 승인한 뒤 stable city identity, time-scoped alias, directed adjacency claim, region codebook, provenance/license schema와 exact-image coordinate versioning을 정의해야 한다.
- **OPENSAM-105:** 승인된 103 contract와 rights-cleared rows만 v1/v2 city contract로 구현한다. `MapJson.connections`, `region`, `x/y` 또는 v2 `PhysicalPlace/RouteCorridor`를 이 문서만으로 채우면 안 된다.

## 11. 명시적 UNKNOWN과 stop reason

- RTK14 46개 source city row는 직접 고정됐지만 opensamguk stable ID·alias/cross-version reconciliation은 `[UNKNOWN]`; 338 region 전 행의 parent reconciliation도 미수행.
- RTK14 전체 adjacency graph, 특히 `洛陽–晋陽` claim의 실제 game edge 의미.
- RTK8R 51개 city page 전체의 `隣接都市` coverage, direction, symmetry, edge count.
- RTK14 `PK.png` native center는 확보됐지만 projection, map-to-world transform, crop/scale policy, historical location, runtime asset 대응은 `UNKNOWN`.
- map image의 저작자별 권리와 opensamguk redistribution/derivative/runtime permission.
- WIKIWIKI text/table compilation의 downstream reuse 권리와 database-right 적용.
- RTK8R official manual page의 ETag/Last-Modified와 byte hash. Fetcher가 header/raw archive를 노출하지 않았고 asset을 다운로드하지 않았다.
- v1 `region` integer codebook과 RTK의 region/province/338-area 중 어느 의미가 대응하는지.
- `EvidenceRef.sourceType`/license enum의 최종 값과 OPENSAM-105 GitHub key.

이 UNKNOWN들은 실패를 숨긴 것이 아니라 stop condition이다. native-pixel ledger는 source-image presentation 측정만 닫았고, source 권리·projection/world meaning·전수 adjacency·runtime adoption은 추측으로 닫지 않는다.

## 12. Validation note

이 파일은 현재 untracked이므로 `git diff --check -- <path>`가 조용히 끝나도 파일 내용을 검사한 증거가 아니다. 검증은 UTF-8 bytes를 직접 읽어 nonempty/EOF newline/trailing whitespace를 검사하고, 별도로 `git diff --no-index --check /dev/null <path>`의 whitespace diagnostic이 비어 있음을 확인한다. `--no-index`의 exit `1`은 새 파일 content diff가 존재한다는 뜻이며 whitespace 실패가 아니다.

## 13. Native-pixel coordinate ledger evidence (2026-07-17)

이 절은 OPENSAM-102의 승인된 원본 이미지 측정 lane을 재현 가능하게 고정한다. 산출물은 [coordinate ledger](./2026-07-17-opensam-102-map-coordinate-ledger.csv)이며, 좌표의 의미는 **지정된 PNG의 좌상단 원점 native pixels**로 제한된다. 역사 지리, 게임 simulation world, 투영법 또는 다른 버전 이미지의 좌표가 아니다.

### Source 역할과 범위

| source | 이번 추출에서의 역할 | 채택한 claim | 채택하지 않은 claim |
|---|---|---|---|
| [都市](https://wikiwiki.jp/sangokushi14/%E9%83%BD%E5%B8%82) | city master와 46개 source order 교차 확인 | 46 city identity/name | stable ID, 역사 위치, runtime coordinate |
| [地理](https://wikiwiki.jp/sangokushi14/%E5%9C%B0%E7%90%86) | exact PK map attachment와 city/faction detail-link index | image marker geometry와 label-to-detail-page join | projection, world transform, asset reuse permission |
| [PK.png original](https://cdn.wikiwiki.jp/to/w/sangokushi14/%E5%9C%B0%E7%90%86/%3A%3Aattach/PK.png?rev=ab2f8e0ceae2aeb16f3291ffa88907d0&t=20201213195804) | 유일한 pixel geometry source | inclusive fill bbox와 그 중심 | runtime/bundled asset, historical terrain truth |
| [異民族](https://wikiwiki.jp/sangokushi14/%E7%95%B0%E6%B0%91%E6%97%8F) | 5개 이민족 거점 이름·존재 교차 확인 | 烏桓·鮮卑·羌族·山越·南蛮 | 복수 외교 선행 도시를 단일 parent로 해석하는 것 |
| `地理`의 46 city와 5 ethnic detail links | label 판독과 base kind/parent adjudication | city exact final URL; city page `地域データ`의 port/gate name·kind·parent; ethnic detail identity | proximity-only parent 추정 |

46 city detail links와 5 ethnic detail links는 `地理`의 노출 순서대로 직렬 열람했다. Port 40개와 gate 10개는 각 parent city detail page의 `地域データ`에 표시된 symbol/name/parent를 대조했고, `地域収入` 표도 교차 확인에만 사용했다.

### Frozen fingerprints와 review derivative

| artifact | location | fingerprint |
|---|---|---|
| exact original | repo 밖 quarantined source `/Users/apple/.codex/visualizations/2026/07/17/019f6da9-8684-7500-a561-477b7aea3e48/opensam-102/source/PK.png`; provenance/rights manifest는 같은 디렉터리의 `manifest.md` | SHA-256 `dfe5049245e730ff1f81325157dedfcf1079786e33c6ad8b3970140954910a89`; PNG RGB `4181×4191`; `281922` bytes; terrain/coordinate research only |
| coordinate ledger | `docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv` | SHA-256 `d0a6c9dab6f1233588ff1a753b84f83fc338b20cb87c5a39f2f043d47512c5c5`; UTF-8 CSV; header 1 + data 101 lines; includes the A2 semantic-swap correction and cleared review state below |
| annotated coordinate review copy | `/Users/apple/.codex/visualizations/2026/07/17/019f6da9-8684-7500-a561-477b7aea3e48/opensam-102/PK-annotated-coordinate-review.png` | SHA-256 `8a70167f07be23362ab0ea4879a4cf6ba53e84c2deedb2815269e36d171f1d18`; full-size `4181×4191` derivative |

주석 derivative에는 city `C001..C046`과 small base `B001..B055`의 bbox/center를 겹쳐 표시했다. Cyan은 city, magenta는 port, orange는 gate, green은 ethnic stronghold이다. 원본 저작물의 redistribution permission이 확인되지 않았으므로 원본과 derivative 어느 것도 저장소·runtime asset으로 bundle하지 않는다.

### Extraction and adjudication procedure

1. 사용자가 지정한 exact attachment를 fresh temp directory에 저장하고 SHA-256, byte size, format, dimensions를 먼저 고정한 뒤, 검증된 bytes를 위 non-repo quarantined source path에 보존했다.
2. RGB exact-color image에서 4-connected fill components를 추출했다. Yellow `RGB(255,255,0)`의 `area>=1000`, width `50..70`, height `30..42` 필터는 51 plaques를 냈고, pink `RGB(242,220,219)`의 `area>=1000`, width `45..70`, height `20..30` 필터는 40 port plaques를 냈다. Enclosed white plaque fill은 vertical/horizontal shape constraint로 10 gate plaques를 냈다.
3. 모든 candidate를 8x nearest-neighbor contact sheet와 full-resolution image inspection으로 직접 읽었다. 자동 색 검출만으로 이름을 정하지 않았다.
4. 51 yellow plaques는 source city/faction links로 46 `CITY`와 5 `ETHNIC_STRONGHOLD`로 분리했다. 40 pink와 10 white candidates는 linked parent-city detail rows로 각각 `PORT`와 `GATE`로 판정했다.
5. Inclusive fill bbox를 `xmin:ymin:xmax:ymax`로 보존했다. 중심은 `x_px=(xmin+xmax)/2`, `y_px=(ymin+ymax)/2`; normalized 값은 `x_norm=x_px/4181`, `y_norm=y_px/4191`이다. Half-pixel center를 정수로 임의 반올림하지 않았다.
6. `CITY` ordinal은 `都市` source order `001..046`을 보존했다. `SMALL_BASE` ordinal은 native center `(y,x)` 오름차순 `001..055`이며 수량 목표를 맞추기 위한 생성 번호가 아니다.
7. 전체 크기 주석 derivative를 다시 열어 ID, center, label을 자가 판독했다. 각 CSV row는 exact original hash/dimensions, detail URL, source locator, method, confidence, review state를 자체 보유한다.

OpenCV, SciPy, scikit-image가 이 환경에 없어 해당 경로는 사용하지 않았다. 설치된 Pillow `12.2`와 NumPy `2.4.6`로 동일한 4-connected component/geometry 절차를 수행했다. 이는 tool availability 기록이며 source-data ambiguity가 아니다. 초기 port bbox serialization에서 component area가 다섯 번째 값으로 섞인 오류는 주석 생성 중 즉시 검출됐고, CSV를 4-value inclusive bbox로 수정한 뒤 전체 validation을 다시 수행했다.

### Coverage and invariants

| entity | rows | observed classification |
|---|---:|---|
| `CITY` | 46 | yellow city plaque |
| `SMALL_BASE / PORT` | 40 | pink port plaque |
| `SMALL_BASE / GATE` | 10 | enclosed white gate plaque |
| `SMALL_BASE / ETHNIC_STRONGHOLD` | 5 | yellow faction plaque |
| **total** | **101** | 46 city + 55 small base |

Producer validation requires and records all of the following: data rows `101`, unique centers `101`, duplicate bbox `0`, image-bounds violation `0`, city ordinals contiguous `001..046`, small-base ordinals contiguous `001..055`, blank/unreadable label `0`, `LOW` confidence `0`, and unknown geometry `0`. `parent_city=UNKNOWN` is present only on the five ethnic strongholds because their pages list multiple diplomacy prerequisite cities rather than one parent.

The final UTF-8 CSV parser assertion also recomputed every center and normalized value from its bbox, checked exact source hash/dimensions on every row, checked all required fields and kind counts, and passed EOF-newline/trailing-whitespace checks. `git diff --check` on the two owned paths passed. Repository-wide `tools/agent-system/check.py` was also run; its only error was the existing project `codex-surface` rule (personal model pin in unchanged `.codex/config.toml`), outside this lane's two-file ownership and unrelated to coordinate data.

### Ambiguity and review ledger

- Unreadable marker label: none after 8x/full-resolution adjudication.
- `鄴` detail link intentionally resolves to the half-width `ｷﾞｮｳ` slug, and `下邳` to the `下ヒ` slug. CSV preserves the resolved detail URLs while canonical names follow the city source row.
- The map plaque reads `羌`; canonical faction/detail-page name is `羌族`. Both `source_label` and `canonical_name` are retained.
- The five ethnic bases deliberately keep `parent_city=UNKNOWN`; prerequisite-city lists are not parentage evidence.
- A2 review found that the producer ledger had exchanged the semantic metadata of two port geometries. It is corrected without moving geometry or ordinal: `B032 / 4108:2449:4170:2472 / (4139,2460.5)` is `婁港 → 呉`, while `B041 / 1429:2790:1479:2813 / (1454,2801.5)` is `巫港 → 永安`. Names, parent cities, detail URLs and source locators moved together; bboxes, centers, normalized values and stable row ordinals did not change.
- The annotated review PNG required no pixel change: it overlays only the stable `B032`/`B041` IDs and geometry on the original plaques, whose glyphs already read `婁港` and `巫港`. Its SHA-256 therefore remains `8a70167f07be23362ab0ea4879a4cf6ba53e84c2deedb2815269e36d171f1d18`.
- All rows are `HIGH` producer confidence with `SELF_ADJUDICATED;A2_INDEPENDENT_REVIEW_CLEARED_2026-07-17;SPEC_DATA_IMAGE_PASS;RIGHTS_WARN`. A2 first issued `fix-required` for the two-row semantic swap, then independently rechecked the corrected artifacts on `2026-07-17`: `SPEC/DATA/IMAGE PASS`, `RIGHTS WARN`, remaining `fix-required` none. The rights warning remains load-bearing and does not authorize asset bundling or redistribution.
- Projection, map-to-world transform, crop/scale policy, historical location and runtime asset correspondence remain `UNKNOWN`.
- Image authorship, derivative/reuse and redistribution permission remain `UNKNOWN`; this pass is research measurement only and bundles no image.
- `.ai/task.md` described the earlier broad baseline, while the parent-approved bounded OPENSAM-102 coordinate lane authorized this extraction. The mismatch is recorded as baseline scope drift, not used to expand the lane.

Status is therefore **A2-cleared for exact-image native-pixel extraction (`SPEC/DATA/IMAGE PASS`) with `RIGHTS WARN`**. OPENSAM-103/105 must still require exact-image version/scaling semantics, stable identities and rights-cleared presentation assets before importing these values into `MapJson`, `ScenarioJson` or v2 placement records.
