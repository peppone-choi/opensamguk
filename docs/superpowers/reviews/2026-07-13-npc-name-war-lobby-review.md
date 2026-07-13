# NPC 이름·전쟁 선포·로비 링크 운영 버그 리뷰

작성일: 2026-07-13

## 결론

로컬 구현·회귀 게이트와 독립 검토는 PASS다. 운영 s1 승격 및 1분 턴 재측정 전까지 배포 판정은 대기한다.

## 운영 기준선

- 턴 데몬은 60초 간격으로 게임 날짜와 성공 tick을 계속 증가시켰고 failed tick은 0이었다.
- `괴포` 사망 3건은 동일 장수의 중복 처리가 아니라 서로 다른 general ID 3117, 3119, 3121의 1회 사망이었다.
- `비포` 재임관처럼 보인 기록도 기존 장수와 별개 ID가 같은 표시명을 재사용한 결과였다.
- 모든 외교 상태가 평화였고 최근 약 57게임년간 신규 전쟁이 없었다. 실행 로그에는 `인접 국가가 아닙니다.` 거부가 반복됐다.

## 원인 가설과 런타임 증거

1. **장수 삭제/flush 실패** — 기각. 반복 로그의 ID가 모두 달랐고 각 ID의 사망 로그는 한 건이었다. world 삭제와 tombstone 경로도 ID를 제거했다.
2. **프론트 새로고침 중 동일 로그 중복 렌더** — 기각. PostgreSQL에 서로 다른 ID의 로그 행이 실제로 세 건 존재했다.
3. **인재탐색 이름 풀 중복 회피 누락** — 확정. PHP `RandomNameGeneral.php:30-62`는 `AbsGeneralPool.php:79-85`와 `GeneralBuilder.php:42-52`의 prefix 목록으로 중복을 검사하지만 Kotlin은 세 이름 조각을 직접 추첨했다. 단일-prefix 접미사와 중복 `ⓝ` 재추첨 테스트가 red/green 경계를 고정한다.
4. **턴 데몬 또는 월간 파이프라인 정지** — 기각. 60초마다 날짜·성공 tick·lastTurnTime이 전진했다.
5. **AI가 선전포고 후보를 전혀 만들지 않음** — 기각. 운영 로그의 인접국 거부는 후보와 실행 시도가 존재함을 증명했다.
6. **최종 nation command full gate의 인접국 문맥 누락** — 확정. 실제 인접국을 넣은 resolver 테스트가 TRADE에 머물렀다가 `__isNeighbor` 공급 후 양방향 DECLARATION으로 전이했다.
7. **보급 불가 도시까지 인접으로 허용** — 독립 리뷰에서 확정. PHP `Constraint/NearNation.php:23`은 `isNeighbor(..., false)`를 사용한다. 무보급 목적지 테스트가 수정 전 DECLARATION, 수정 후 TRADE를 보였다.
8. **로비 링크가 CSS나 path-server 라우팅으로 숨음** — 기각. 수정 전 DOM에 접근 가능한 `로비로` 링크 자체가 없었다. 링크 추가 후 같은 테스트가 `/lobby`를 확인했다.

## 변경 계약

- NPC 이름 선택은 PHP의 first/middle/last RNG 3회, prefix별 `LIKE name%` 합산, 2건 이상 재추첨, 100번째 또는 1건 중복의 숫자 접미사 순서를 그대로 따른다.
- `existingGeneralNames`는 현재 in-memory world에서 공급하므로 같은 tick에 생성된 장수도 다음 인재탐색에서 보인다.
- 선전포고 후보의 full gate와 실제 실행 full gate는 모두 공급 도시만 사용한 인접 판정을 공유한다.
- 로비 링크는 운영 동일 출처에서는 `/lobby`, 분리 개발 환경에서는 `NEXT_PUBLIC_GATEWAY_URL` 또는 `NEXT_PUBLIC_GATEWAY_ORIGIN`을 사용한다.
- persistence write 경로와 dependency에는 변화가 없다.

## 검증

- `tools/parity/gate.sh backend`: 최종 diff 기준 471 suites / 3,604 tests / fail 0.
- `:logic:test`: PASS. 이름 집중 테스트 2/2 PASS.
- `NationCommandDispatchTest`: 실제 인접 허용과 무보급 인접 거부를 포함해 18/18 PASS.
- `web/game`: 37 files / 148 tests PASS, typecheck PASS, production build PASS.
- `web/gateway`: typecheck PASS.
- `tools/agent-system/check.py --strict --base origin/main`: error 0, warning 0.

## 독립 review-work 판정

| lane | 판정 | 핵심 |
|---|---|---|
| 목표·제약 | PASS | 세 사용자 요구와 최소 변경·ONE daemon write 준수 |
| 실행 QA | PASS | 이름 2/2, 선전포고 18/18, 로비 DOM 6/6 |
| 코드 품질 | PASS | 무보급 인접 차단 발견 후 수정·재리뷰 완료 |
| 보안 | PASS | 신규 사용자 입력 sink, 권한 우회, secret, dependency 없음 |
| 맥락 감사 | PASS | PHP 중복 `ⓝ` prefix의 재추첨 quirk까지 직접 재확인 |

## 운영 재측정 체크리스트

- s1 이미지 태그가 새 commit으로 바뀌고 game-api/game-engine/web-game health가 UP인지 확인한다.
- 60초 턴이 2회 이상 성공하고 failed tick이 증가하지 않는지 확인한다.
- 신규 선전포고 로그와 diplomacy DECLARATION 상태가 생성되는지 확인한다.
- 배포 이후 생성된 인재탐색 NPC가 기존 표시명을 그대로 재사용하지 않는지 확인한다.
- 실제 브라우저에서 메인의 `로비로`를 클릭해 `/lobby`로 이동하고 4xx/5xx·page error가 없는지 확인한다.
- 검증 후 정상 60분 turnterm으로 복원한다.
