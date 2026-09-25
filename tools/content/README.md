# 2·3층 콘텐츠 원장

새 원장은 파일명에 판 번호를 붙이지 않고 루트 `schemaVersion: 1`을 둔다. `rows`의 각 행은 고유한 `id`, `grade`, `sources`를 가진다. `grade`는 `PRIMARY`, `ROMANCE`, `SCHOLARLY`, `GAME_TERM` 중 하나다.

역사 행의 각 인용에는 `book`, `volume`, `section`, `quote`, `grade`를 모두 채운다. `grade`는 행과 일치해야 한다. 정사와 연의는 한 행에 섞지 않는다. `GAME_TERM` 행은 사료 주장 없이 `sources: []`를 허용하지만, `gameTermReason`과 `displayBadge: "게임 용어"`가 필요하다. 다른 행을 가리키는 `refs`는 같은 원장에 존재하는 ID만 적는다.

게임 수치는 개별 `{ "value": 숫자, "status": "CONFIRMED", "decidedBy": "구현 에이전트", "decidedAt": "YYYY-MM-DD", "basis": "근거" }` 객체로 둔다. 사용자 확정값은 원래 판정자를 보존한다. 수치를 담은 객체의 필수 필드는 위치에 관계없이 검사한다.

```
python3 tools/content/validate_ledger.py data/curated/han/local-offices.json
python3 -m unittest discover -s tools/content/tests -p 'test_validate_ledger.py'
```

JVM 소비자는 `ContentLedgerValidator.requireValid`을 거친 뒤 행을 읽는다. 이 구조 검사는 인용이 실제 사료에 있는지 증명하지 않는다. 역사 행은 별도 사료 색인에서 책·권·편과 원문을 대조한다.

## 행정 축

`HanAdministrativeAxis.loadPinned()`은 기본 세계 지도와 州 축의 SHA-256을 검사하고, 정본 郡國에 소속된 縣만 관직 관할 대상으로 돌린다. #905 지도 병합 뒤에도 105 郡國의 1,127 縣이 연결된다. `OUTSIDE_CANON` 61개와 `UNRESOLVED_PARENT` 259개는 관할권을 부여하지 않는다. 기본 城 번들의 `isSeat`로 101 郡國의 치소 城 ID를 읽는다. 清河國·泰山郡·齊國·張掖屬國 4곳은 `isSeat` 城이 없어 `baseSeatFor`가 null을 돌려준다. 이름으로 치소를 추측하지 않는다. 월드별 행정 변경은 별도 오버레이 투영이며 이 기본 핀이 그 투영을 대체하지 않는다.
