# Han B1 寧陽 부모 소속 수정

## 결론

물리·관할 ID `45277`(`宁阳县`, 사료 표기 `寧陽`)의 220년 기준 소속을
`PARENT-0028 山陽郡`에서 `PARENT-0024 東平國`으로 수정했다. 다른 縣, 기하,
런타임 도시 카탈로그는 변경하지 않았다.

## 사료와 판정

- 1차 근거: `references/sources/shiliao/corpus/hhs-111.txt:85-101`. `東平國` 七城 목록
  끝에 `〖-{寧}-陽〗故屬泰山。`이라고 적혔다.
- 재검색 가능한 공개 참조:
  `https://zh.wikisource.org/wiki/後漢書/卷111#東平國`.
- 지도의 현재 정확 이름은 간체 `宁阳县`이므로 원장의 이름 드리프트 검사에는
  이 값을 쓰고, 사료 인용은 원문 `寧陽`을 보존한다.
- 실행 원장은 `APPROVED_EXACT_PARENT` 1행으로 한정했다.

## 정확한 변경 범위

- `jurisdictionRecords[45277].commanderyId`: `PARENT-0028` → `PARENT-0024`.
- 해당 `provinceRecords` 1건의 `parentRegionId`: `PARENT-0028` → `PARENT-0024`.
- `commanderyRecords` 회원 수: 東平國 6 → 7, 山陽郡 10 → 9. 두 郡國의 치소는 불변.
- `parentOwner`: 33셀만 배열 순번 28 → 24. `owner`와 기하는 불변.
- commandery adjacency는 위 셀에서 결정론적으로 재계산되어 `adjCommandery`가
  414 → 413이 됐다. county adjacency 4,161은 불변.
- 해결된 단절 `PARENT-0028@452:210`(33셀) 원장 한 행만 제거했다.
  단절 인벤토리는 120 → 119이고 대체 단절은 없다.
- `han-tiles.json` SHA-256 / bytes:
  `8291649cf6636c289d60bb980ce7f50080965a9ca16ca01630604524be307520` / 2,312,239 →
  `237b8f1d8a8228a89fa251b4020ef254c2176aedd4ba3133c4996a630bd07a63` / 2,312,160.

## 불변식과 호환성

- 1,524 provinces, 1,020 jurisdictions, 172 commanderies와 모든 안정 ID를 보존했다.
- `terrain`, `owner`, `seatOwner`, `cities`, `juns`, `parentRegions`, county adjacency는
  수정 전후 동일하다. 좌석·좌표·도시 수는 변하지 않았다.
- 15개 시나리오의 province ownership assignments는 수정 전후 동일하다.
  변경된 것은 `sources.mapSha256`뿐이다.
- 기존 `han.json`, `han-world-v3.json`, schema-1 / schema-2 supply ledger는 각각 바이트
  동일하다. `45277` route-node selection 추가는 0건이다.
- 수계는 2 zones, 0 traversal edges, 0 barriers, 0 ports를 유지했고 기저 해시와
  의존 manifest만 재결합했다.

## 검증과 남은 위험

- RED는 원장 행 부재, 이전 부모 `PARENT-0028`, 남아 있는 단절 행을 각각 검출했다.
- 최종 집중 Python 114 tests는 GREEN이다. 리뷰 후속으로 기존 fail-closed helper의
  unknown jurisdiction/source commandery/target commandery ID 분기를 직접 검증하는
  테스트 3개를 추가했다. 이 테스트들은 기존 동작을 특성화하므로 첫 실행부터
  GREEN이었고 구현 RED로 세지 않는다. 10개 materializer/audit `--check`도 드리프가 없다.
  `apply_han_world.py --check`는 15개 시나리오 변경 0건과 기존 경고 8건을 보고했다.
- 광역 map 653 tests(40 skips) GREEN은 위 테스트 전용 후속 직전 트리의 결과이며,
  테스트 전용 변경 뒤에는 재실행하지 않았다. 최종 트리는 PR CI가 검증한다.
- 부모가 직렬 실행한 JDK 21 Gradle 대상 4개 클래스는 49 tests, 실패·오류·skip 0으로
  GREEN이다. JVM RED는 실행하지 않았고 Python 행동 RED를 사용했다.
- 미해결 B1: `40377 永縣`은 기존 supply-policy source reference를 깨므로 제외했다.
  `40165 東部侯官縣`은 새 `GEOMETRY_DEFECT` 판정이 필요해 제외했다.
  둘 다 이 수정으로 완료된 것으로 취급하지 않는다.

docs-impact: internal research and deterministic artifact bindings only; player/admin behavior and
operational procedures are unchanged.
