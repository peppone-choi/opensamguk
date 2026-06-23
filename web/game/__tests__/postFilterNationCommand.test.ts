import { describe, expect, it } from 'vitest';
import { postFilterNationCommandGen, type TurnObj } from '@/lib/utilGame/postFilterNationCommandGen';

// legacy hwe/ts/utilGame/postFilterNationCommandGen.ts + JosaUtil.pick(name,'로') 패러티 락.
// 핵심: che_발령 brief를 부대장 dest일 때만 《부대명》【도시명】{조사} 발령으로 후처리하고,
// 조사는 legacy checkCode(isRo) — 받침없음(0)/ㄹ받침(8) → '로', 그 외 → '으로'.
describe('postFilterNationCommandGen', () => {
    const cityConst = [
        { id: 67, name: '산월' }, // ㄹ받침 → '로' (단순 %28 근사가 깨지는 케이스)
        { id: 1, name: '성도' }, // 받침 없음 → '로'
        { id: 2, name: '업' }, //   ㅂ받침 → '으로'
    ];
    const troopList: Record<number, string> = { 5: '선봉대' };
    const filter = postFilterNationCommandGen<TurnObj>(troopList, cityConst);

    function turn(arg: TurnObj['arg'], action = 'che_발령', brief = 'orig'): TurnObj {
        return { action, brief, arg };
    }

    it('ㄹ받침 도시(산월)는 「으로」가 아니라 「로」 발령으로 후처리', () => {
        const out = filter(turn({ destGeneralID: 5, destCityID: 67 }));
        expect(out.brief).toBe('《선봉대》【산월】로 발령');
    });

    it('받침 없는 도시(성도)는 「로」 발령', () => {
        const out = filter(turn({ destGeneralID: 5, destCityID: 1 }));
        expect(out.brief).toBe('《선봉대》【성도】로 발령');
    });

    it('받침 있는 도시(업)는 「으로」 발령', () => {
        const out = filter(turn({ destGeneralID: 5, destCityID: 2 }));
        expect(out.brief).toBe('《선봉대》【업】으로 발령');
    });

    it('dest가 부대장이 아니면(troopList 미포함) brief 원본 유지', () => {
        const out = filter(turn({ destGeneralID: 999, destCityID: 67 }));
        expect(out.brief).toBe('orig');
    });

    it('che_발령이 아닌 명령은 passthrough(brief 불변)', () => {
        const out = filter(turn({ destGeneralID: 5, destCityID: 67 }, 'che_휴식', '휴식'));
        expect(out.brief).toBe('휴식');
    });
});
