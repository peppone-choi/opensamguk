// 서신 데이터 층 — 방향 · 지우기 표식 · 받는 사람(NPC 포함, 서버 대기 사유) · 보이는 글자 수.
import { describe, expect, it } from 'vitest';
import { latestReceivedId, mailboxIdOf, toMailItems, type RecentMailRow } from '@/lib/mail/mail-model';
import { NPC_MAIL_WAITING, toRecipientOptions } from '@/lib/mail/recipients';
import { isBlank, visibleLength } from '@/lib/mail/text';
import type { PublicGeneral } from '@/types/game';

const me = { generalId: 1, nationId: 3 };
const who = (id: number, nation_id = 3) => ({ id, name: `[${id}]`, nation_id, nation: nation_id ? '[세력]' : '재야', color: '#123456' });
const row = (over: Partial<RecentMailRow>): RecentMailRow => ({ id: 10, msgType: 'private', src: who(2), dest: who(1), text: '<b>안녕</b>', option: null, time: new Date().toISOString(), ...over });

describe('서신 목록 모델', () => {
    it('보낸 장수가 나면 「보냄」, 아니면 「받음」 — 재야는 소속 null', () => {
        const items = toMailItems({ private: [row({ id: 11, src: who(1), dest: who(2, 0) }), row({ id: 12 })], public: [], national: [], sequence: 0 }, 'private', me);
        expect(items.map((i) => i.direction)).toEqual(['sent', 'received']);
        expect(items[0].to?.nation).toBeNull();
        expect(items[1].from?.nation).toEqual({ id: 3, name: '[세력]', color: '#123456' });
        expect(latestReceivedId(items)).toBe(12);
    });

    it('엔진 지우기 표식 행은 빼고, 지운 서신은 본문 없이 남긴다', () => {
        const items = toMailItems({ private: [row({ id: 20, text: 'req_del_msg' }), row({ id: 21, option: { invalid: true } })], public: [], national: [], sequence: 0 }, 'private', me);
        expect(items.map((i) => i.id)).toEqual([21]);
        expect(items[0].html).toBeNull();
    });

    it('지우기는 내가 보낸 5분 안의 서신만(지금 규칙 그대로)', () => {
        const now = Date.parse('2026-10-01T00:10:00Z');
        const fresh = row({ id: 30, src: who(1), time: '2026-10-01T00:06:00Z' });
        const old = row({ id: 31, src: who(1), time: '2026-10-01T00:00:00Z' });
        const theirs = row({ id: 32, time: '2026-10-01T00:09:00Z' });
        const items = toMailItems({ private: [fresh, old, theirs], public: [], national: [], sequence: 0 }, 'private', me, now);
        expect(items.map((i) => i.deletable)).toEqual([true, false, false]);
    });

    it('재야는 세력 서신함이 없다', () => {
        expect(mailboxIdOf('national', { generalId: 1, nationId: 0 })).toBeNull();
        expect(mailboxIdOf('national', me)).toBe(9003);
        expect(mailboxIdOf('public', me)).toBe(9999);
    });
});

describe('받는 사람(사람 고르기)', () => {
    const g = (over: Partial<PublicGeneral>) => ({
        generalId: 2, name: '가', nationId: 3, nationName: '[세력]', nationColor: '#111111', npc: 0, officerLevel: 1, cityName: '허현',
        picture: null, imageServer: 0, ...over,
    }) as PublicGeneral;

    it('NPC도 보이되 서버 대기 사유로 막는다 · 나는 뺀다 · 묶음은 서버 값으로', () => {
        const opts = toRecipientOptions([
            g({ generalId: 1, name: '나' }), g({ generalId: 2, name: '다' }),
            g({ generalId: 3, name: '나무', npc: 2 }), g({ generalId: 4, name: '가', nationId: 5, officerLevel: 12, cityName: '' }),
            g({ generalId: 5, name: '라', nationId: 0 }),
        ], me);
        expect(opts.map((o) => o.generalId)).toEqual([4, 3, 2, 5]);
        expect(opts.find((o) => o.generalId === 3)).toMatchObject({ isHuman: false, blockedReason: NPC_MAIL_WAITING });
        expect(opts.find((o) => o.generalId === 2)).toMatchObject({ isHuman: true, groups: ['nation'], location: '허현' });
        const ruler = opts.find((o) => o.generalId === 4)!;
        expect(ruler.groups).toEqual(['rulers']);
        expect('location' in ruler).toBe(false);
        expect(opts.find((o) => o.generalId === 5)?.nation).toBeNull();
    });
});

describe('보이는 글자 수', () => {
    it('서식 태그는 세지 않고 줄바꿈 · 문자 참조는 한 글자', () => {
        expect(visibleLength('<b>가나</b><span style="color:red">다</span>')).toBe(3);
        expect(visibleLength('가<br>나&amp;다&#x1F600;')).toBe(6);
        expect(isBlank('<p> </p><br>')).toBe(true);
    });
});
