// 城 이 차지하는 칸 — 「성내」.
//
// 칸이 게임 단위이므로(군사는 구역, 내정은 縣, 건물은 칸) 城 도 칸을 차지해야 한다. 등급이 높은
// 城 은 넓다. 2026-09-23 사용자 결정: **경 7 · 특 5 · 대 5 · 중 3 · 나머지 1(영현 포함)** — 변은 모두
// 홀수다. 짝수 변은 중심이 칸이 아니라 경계라 城 마커 칸이 한가운데가 되지 못한다(2026-09-22 판 5·4·3·2·2·1 을 바꿨다).
// 영현을 3칸으로 두면 縣 이 2–3 칸 간격인 밀집지에서 성내가 겹치는 城 이 92 곳이라 1칸으로 둔다(겹침 35 곳).
// 그래도 남는 겹침은 [resolveCityFootprints] 가 푼다.
//
// 등급 필드에는 **두 축**이 섞여 있다(`CityLevelList.kt` 주석이 그렇게 말한다).
//   - 1–9 수·진·관·이·소·중·대·특·경 — legacy 규모 등급. 郡治·수도·특수 거점 209 곳에만 붙는다.
//   - 10 영현 · 11 장현 — 後漢 百官志 의 縣 구분(「萬戶以上為令，不滿為長」). 나머지 924 곳이다.
// 두 축은 같은 자로 못 재므로 따로 둔다. 영현이 장현보다 넓은 것은 정의가 호구 1만 이상이기
// 때문이고, 우리가 정한 수치가 아니다.
//
// 삼모도 등급별 footprint(7×7~4×4)를 쓰지만 그건 12×12 좌표에 53 성을 놓은 지도 기준이다.
// 여기 격자는 768×669 이고 구역당 평균 165 칸이라, 가장 큰 城(경, 49 칸)도 성외 공사 칸을 남긴다.
//
// 등급 번호는 `CityLevelList.kt` 의 정본이다 — 수1·진2·관3·이4·소5·중6·대7·특8·경9·영현10·장현11.

/** 성내 한 변의 칸 수. */
export function cityFootprintSpan(level: number): number {
    if (level === 9) return 7; // 경 — 수도
    if (level === 8) return 5; // 특
    if (level === 7) return 5; // 대
    if (level === 6) return 3; // 중
    return 1; // 소·이·관·진·수·영현·장현 — 縣 대부분이 여기다
}

export interface CellBlock {
    readonly col0: number;
    readonly row0: number;
    readonly span: number;
}

/**
 * 성내가 덮는 칸 블록.
 *
 * 변이 모두 홀수라 마커 칸이 늘 성내 한가운데 칸이다.
 */
export function cityFootprintBlock(level: number, col: number, row: number): CellBlock {
    const span = cityFootprintSpan(level);
    const back = Math.floor((span - 1) / 2);
    return { col0: col - back, row0: row - back, span };
}

export interface FootprintCity {
    readonly id: number;
    readonly level: number;
    readonly col: number;
    readonly row: number;
}

/**
 * 이웃 城 과 겹치지 않는 성내 변 — `id → span`.
 *
 * 큰 城 부터 칸을 잡는다(변이 큰 순, 같으면 城 번호 순 — 입력 순서와 무관하게 늘 같은 답). 뒤 城 의
 * 블록이 이미 잡힌 칸과 겹치면 변을 2 씩 줄인다(홀수 유지). 1칸이 되면 그대로 둔다 — 마커 칸끼리
 * 겹치는 것은 지도 데이터 문제라 여기서 숨기지 않는다.
 */
export function resolveCityFootprints(cities: readonly FootprintCity[]): Map<number, number> {
    const order = [...cities].sort((a, b) => cityFootprintSpan(b.level) - cityFootprintSpan(a.level) || a.id - b.id);
    const taken = new Set<string>();
    const spans = new Map<number, number>();
    for (const city of order) {
        const col = Math.round(city.col);
        const row = Math.round(city.row);
        let span = cityFootprintSpan(city.level);
        const cells = (s: number) => {
            const back = (s - 1) / 2;
            const out: string[] = [];
            for (let c = col - back; c <= col + back; c += 1) {
                for (let r = row - back; r <= row + back; r += 1) out.push(`${c},${r}`);
            }
            return out;
        };
        while (span > 1 && cells(span).some((cell) => taken.has(cell))) span -= 2;
        for (const cell of cells(span)) taken.add(cell);
        spans.set(city.id, span);
    }
    return spans;
}
