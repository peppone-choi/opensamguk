# han-map-zoom-lod cross-agent critique

Scope: web/
Verdict: cleared

작성 레인(구현)과 분리된 `code-reviewer` 에이전트가 diff 를 읽고, `HanMapCanvas.test.ts`
전체 실행과 `tsc --noEmit` 을 직접 재현해 독립적으로 검토했다. 자기 승인 없음.

## 변경 요약

`HanMapCanvas.tsx` 의 縣(COUNTY) 마커·라벨 zoom 문턱을 매직넘버 상수
(`COUNTY_ZOOM=2.2`, `COUNTY_LABEL_ZOOM=5.5`) + `c.kind !== 'COUNTY'` 하드코딩
조건문에서, `cities[].kind`(=`AdministrativeContracts.kt:57` 의 `AdministrativeLevel`과
같은 문자열) 로 조회하는 매핑 테이블(`TIER2_MARKER_ZOOM`, `TIER2_LABEL_ZOOM`) +
`tierZoom(table, kind)` 조회 함수로 승격했다. 테이블에 없는 등급(1급 전부, 아직
데이터에 없는 `KINGDOM`)은 `undefined` 를 돌려주고 호출부가 "여기서 안 그림"으로
처리한다.

## 검토 결과

1. **기존 데이터(COUNTY) 동작 동치성 — 확인됨.** 마커 루프·라벨 루프 모두 예전
   조건식과 새 조건식이 `s < 2.2`/`s >= 5.5` 경계에서 완전히 같은 스킵 판정을
   낸다. `s === 임계값` 포함 경계값 드리프트 없음.
2. **일반화 정합성 — 확인됨.** "테이블에 없는 등급은 여기서 안 그림" 이 예전의
   `kind !== 'COUNTY'` 무조건 스킵과 정확히 같은 의미로 보존된다.
3. **KINGDOM 전방 호환 — 타당함.** `KINGDOM` 을 테이블에서 뺀 것은 오늘 COMMANDERY/
   PROVINCE 가 겪는 것과 동일한 처리(여기서 마커로 안 그림, 郡 라벨·治所 마커가
   대표)라서 `jun-guo-split` 이 KINGDOM 을 채워도 회귀가 없다. `juns[]`/郡 라벨
   경로는 이번 변경으로 손대지 않았다.
4. **테스트 품질 — 지적 반영 완료.** 최초 버전은 테스트가 실제 export 테이블이
   아니라 로컬 재선언 상수를 assert 해서, `TIER2_MARKER_ZOOM` 에 실수로
   `KINGDOM` 을 추가해도 테스트가 안 잡는 문제가 있었다(non-blocker 로 표시됐지만
   즉시 수정). `TIER2_MARKER_ZOOM`/`TIER2_LABEL_ZOOM` 을 export 하고 테스트가 그
   실제 테이블을 assert 하도록 고쳤다 — 3줄 변경, 재검증 완료(아래 최종 증거).
5. **기타** — 데드 코드 없음, `COUNTY_ZOOM`/`COUNTY_LABEL_ZOOM` 완전 제거,
   `ctx.font`/`fillStyle`/`lineWidth` 무조건 세팅으로 바뀐 부분은 이후 그리기
   블록이 전부 자기 상태를 다시 세팅해서 캔버스 상태 누수 없음(추적 확인).
   줌아웃 시 라벨 루프가 매 프레임 ~1000개 city 를 순회하는 것(예전엔 `s >= 5.5`
   바깥 가드로 통째로 스킵)은 bounds-check + 객체 인덱스 수준이라 성능 문제
   아님(LOW, 후속 불필요).

## 최종 증거

- `npm --prefix web/game run typecheck` — 통과.
- `npx vitest run __tests__/HanMapCanvas.test.ts`(web/game) — 6/6 통과, 그중
  `tierZoom` 매핑 테스트가 이제 `TIER2_MARKER_ZOOM`/`TIER2_LABEL_ZOOM` 실제
  export 를 대상으로 COUNTY/MARQUISATE 히트와 COMMANDERY/KINGDOM/PROVINCE
  fallback 을 assert.
- `npx vitest run`(전체, web/game) — 76 files / 433 tests 전부 통과.
