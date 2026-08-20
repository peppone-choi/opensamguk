# OPENSAM-215 한 지도 시각 디자인 독립 검토

- 검토일: 2026-08-20
- 대상: `docs/superpowers/specs/2026-08-20-han-map-visual-design.md` 및 연결된 증거 묶음
- 검토 방식: 작성자와 분리된 읽기 전용 디자인 검토자
- 최종 판정: **CLEARED**

## 입력 고정점

- `data/map/han-tiles.json`: SHA-256 `1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d`
- 실제 OPENSAM-209 화면(before): SHA-256 `d686fbddeb5944ba155186402b8f32c361b81544a3be4f3761acfe3e3ff1c7b8`
- 실제 OPENSAM-209 화면(after): SHA-256 `c8dde7b423e8757600de8f2ee41ce0f31bbfb4a1ea956fb032fd3a7214315d63`

## 검토 라운드와 조치

1. 최초 검토는 해안이 郡 경계처럼 보이는 문제, 축소 지도의 소유 레이어·뷰포트 문제, 로컬 라벨 우선순위, 범례 완전성, 캔버스 접근성 계약을 `FIX_REQUIRED`로 판정했다.
2. 생성기는 郡 경계를 육지-육지의 서로 다른 `seatOwner` 이웃으로 제한했다. 검증 결과 郡 경계 16,031개를 그렸고 해안 후보 3,197개를 제외했다.
3. 축소 지도에 동일한 지형·수계·郡·소유 경계를 축약해 그렸고, 라벨 우선순위와 통합 범례를 보완했다. 명세에는 캔버스와 동기화된 DOM 버튼, 24 px 타깃, roving tabindex, 방향키 이동, Enter/Space 선택, 공용 설명과 포커스 유지 계약을 추가했다.
4. 재검토는 고정형 축소 지도 뷰포트 한 건만 남겨 `FIX_REQUIRED`로 판정했다.
5. 최종 생성기는 실제 메인 뷰의 네 화면 모서리를 기존 등각 투영의 역변환으로 셀 좌표화한 뒤 축소 지도 좌표로 재투영하고, 축소 지도 내부에서 클리핑한다.
6. 최종 재검토는 남은 시각 충실도, 토큰, 레이어, 계층, 비날조, 반응형, 접근성 계약 문제가 없음을 확인하고 `CLEARED`를 반환했다.

## 최종 증거

| 파일 | SHA-256 |
|---|---|
| `han-map-direction-overview.png` | `c3a5889a9497812ecd35552af1deef84ff83be1e64552cc0d8dd3934aeec9cd5` |
| `han-map-direction-local.png` | `7299552dc1ef5660dd07152a24119752ded50652ee410d646275c786995a4918` |
| `han-map-token-specimen.png` | `4c66c303752330a7cc7c9bb9ad8acd5448562493bb47ac66296d0846879e134e` |

생성기를 연속 실행해 위 해시가 변하지 않음을 확인했다. 최종 증거 묶음은 `git diff --check`와 `python3 tools/agent-system/check.py --format json`도 통과했다(`ok: true`, findings 없음).

## 제품 결정 체크포인트

검토 통과는 명세의 구현 승인을 뜻하지 않는다. 구현 전 D1(7개 지표면 + 2개 수계 분류), D2(런타임 국가 소유권 결합), D3(소유색 OKLCH 정규화)를 제품 결정으로 확정해야 한다. 정적 `owner`/`seatOwner` 값은 국가 ID로 해석하지 않는다.
