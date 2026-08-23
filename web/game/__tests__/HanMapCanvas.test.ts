import { describe, expect, it } from 'vitest';
import { expandOwner, labelledRegions, seatLabel, tierZoom } from '../components/game/HanMapCanvas';

describe('HanMapCanvas 격자 해제', () => {
    it('런렝스를 셀 배열로 되돌린다', () => {
        expect(Array.from(expandOwner([[-1, 2], [7, 3]], 5))).toEqual([-1, -1, 7, 7, 7]);
    });

    it('격자 크기를 넘는 런렝스는 잘라 낸다 — 손상된 파일이 배열을 늘리지 못한다', () => {
        expect(expandOwner([[1, 999]], 4)).toHaveLength(4);
    });

    it('작은 지역은 라벨을 달지 않는다 — 겹쳐 읽히지 않게', () => {
        const r = (name: string, cells: number) =>
            ({ name, nameCh: name, en: name, cls: 'Range/mtn', col: 1, row: 1, cells });
        expect(labelledRegions([r('太行山', 500), r('조각', 3)]).map((x) => x.name)).toEqual(['太行山']);
    });

    it('성 이름은 治所 縣 이름에서 縣을 뗀다 — 郡 이름이 아니다', () => {
        expect(seatLabel('낙양현')).toBe('낙양');
        expect(seatLabel('회현')).toBe('회');       // 한 글자로 줄어도 사료 그대로
        expect(seatLabel('현')).toBe('현');         // 이름 자체가 '현'이면 그대로
        expect(seatLabel('요동군')).toBe('요동군'); // 縣 기록이 없는 자리는 손대지 않는다
    });
});

describe('등급 → 최소 표시 zoom 매핑', () => {
    const TABLE = { COUNTY: 2.2, MARQUISATE: 2.2 };

    it('테이블에 있는 등급은 그 값을 돌려준다', () => {
        expect(tierZoom(TABLE, 'COUNTY')).toBe(2.2);
        expect(tierZoom(TABLE, 'MARQUISATE')).toBe(2.2);
    });

    it('모르는 등급(郡·國·州, 혹은 아직 없는 KINGDOM)은 undefined — 호출부가 안전하게 안 그린다', () => {
        expect(tierZoom(TABLE, 'COMMANDERY')).toBeUndefined();
        expect(tierZoom(TABLE, 'KINGDOM')).toBeUndefined();
        expect(tierZoom(TABLE, 'PROVINCE')).toBeUndefined();
    });
});
