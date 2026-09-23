// 城 이 차지하는 칸 — 「성내」.
//
// 칸이 게임 단위이므로(군사는 구역, 내정은 縣, 건물은 칸) 城 도 칸을 차지해야 한다. 등급이 높은
// 城 은 넓다. 2026-09-22 사용자 결정: **경 5 · 특 4 · 대 3 · 중 2 · 영현 2 · 나머지 1**.
//
// 등급 필드에는 **두 축**이 섞여 있다(`CityLevelList.kt` 주석이 그렇게 말한다).
//   - 1–9 수·진·관·이·소·중·대·특·경 — legacy 규모 등급. 郡治·수도·특수 거점 209 곳에만 붙는다.
//   - 10 영현 · 11 장현 — 後漢 百官志 의 縣 구분(「萬戶以上為令，不滿為長」). 나머지 924 곳이다.
// 두 축은 같은 자로 못 재므로 따로 둔다. 영현이 장현보다 넓은 것은 정의가 호구 1만 이상이기
// 때문이고, 우리가 정한 수치가 아니다.
//
// 삼모도 등급별 footprint(7×7~4×4)를 쓰지만 그건 12×12 좌표에 53 성을 놓은 지도 기준이다.
// 여기 격자는 768×669 이고 구역당 평균 165 칸이라, 가장 큰 城(경, 25 칸)도 성외 공사 칸을 남긴다.
//
// 등급 번호는 `CityLevelList.kt` 의 정본이다 — 수1·진2·관3·이4·소5·중6·대7·특8·경9·영현10·장현11.

/** 성내 한 변의 칸 수. */
export function cityFootprintSpan(level: number): number {
    if (level === 9) return 5; // 경 — 수도
    if (level === 8) return 4; // 특
    if (level === 7) return 3; // 대
    if (level === 6) return 2; // 중
    if (level === 10) return 2; // 영현 — 縣令, 호구 1만 이상
    return 1; // 소·이·관·진·수·장현 — 縣 대부분이 여기다
}

export interface CellBlock {
    readonly col0: number;
    readonly row0: number;
    readonly span: number;
}

/**
 * 성내가 덮는 칸 블록.
 *
 * 홀수 변은 마커 칸을 중심에 둔다. **짝수 변은 중심이 칸 하나가 아니라 경계**여서 어느 쪽으로
 * 붙일지 정해야 하는데, 마커 칸을 왼쪽·위로 삼는다 — 정해 두지 않으면 같은 城 이 판마다 다른
 * 칸을 차지한다.
 */
export function cityFootprintBlock(level: number, col: number, row: number): CellBlock {
    const span = cityFootprintSpan(level);
    const back = Math.floor((span - 1) / 2);
    return { col0: col - back, row0: row - back, span };
}
