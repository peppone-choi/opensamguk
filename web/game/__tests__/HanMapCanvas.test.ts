import { describe, expect, it } from 'vitest';
import { borderCells, expandOwner, labelledRegions } from '../components/game/HanMapCanvas';

describe('HanMapCanvas 격자 해제', () => {
    it('런렝스를 셀 배열로 되돌린다', () => {
        expect(Array.from(expandOwner([[-1, 2], [7, 3]], 5))).toEqual([-1, -1, 7, 7, 7]);
    });

    it('격자 크기를 넘는 런렝스는 잘라 낸다 — 손상된 파일이 배열을 늘리지 못한다', () => {
        expect(expandOwner([[1, 999]], 4)).toHaveLength(4);
    });

    it('작은 지역은 라벨을 달지 않는다 — 겹쳐 읽히지 않게', () => {
        const r = (name: string, cells: number) =>
            ({ name, en: name, cls: 'Range/mtn', col: 1, row: 1, cells });
        expect(labelledRegions([r('太行山', 500), r('조각', 3)]).map((x) => x.name)).toEqual(['太行山']);
    });

    it('라벨이 갈리는 자리만 국경으로 뽑는다 — 바다(-1)는 국경이 아니다', () => {
        // 2×2. 왼쪽 열은 郡0, 오른쪽 위는 郡1, 오른쪽 아래는 바다.
        const g = Int16Array.from([0, 1, 0, -1]);
        expect(borderCells(g, 2, 2)).toEqual([0, 1, 2]);
    });
});
