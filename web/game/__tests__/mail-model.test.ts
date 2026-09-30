// 서신 데이터 층 — 방향 · 지우기 표식 · 받는 사람(NPC 포함, 서버 대기 사유) · 보이는 글자 수.
import { describe, expect, it } from 'vitest';
import { toDiplomacyWrite } from '@/lib/mail/diplomacy';
import { DIPLOMACY_MASK_TEXT, latestReceivedId, mailboxIdOf, toMailItems, type RecentMailRow } from '@/lib/mail/mail-model';
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

describe('외교 서신', () => {
    const env = (diplomacy: RecentMailRow[]) => ({ private: [], public: [], national: [], diplomacy, sequence: 0 });
    // 외교 서신의 받는 쪽은 장수 없이 세력만 온다(엔진 MsgTarget(0, "", 세력)).
    const nationOnly = (nation_id: number, nation: string) => ({ id: 0, name: '', nation_id, nation, color: '#654321' });

    it('보낸 세력이 우리 세력이면 「보냄」 — 내가 아닌 외교권자가 보내도, 받는 세력 이름은 dest 에서', () => {
        const items = toMailItems(env([
            row({ id: 40, msgType: 'diplomacy', src: who(7, 3), dest: nationOnly(5, '[원소]') }),
            row({ id: 41, msgType: 'diplomacy', src: who(8, 5), dest: nationOnly(3, '[세력]') }),
        ]), 'diplomacy', me);
        expect(items.map((i) => i.direction)).toEqual(['sent', 'received']);
        expect(items[0].to).toBeNull();
        expect(items[0].toNation).toEqual({ id: 5, name: '[원소]', color: '#654321' });
        expect(latestReceivedId(items)).toBe(41);
        expect(mailboxIdOf('diplomacy', me)).toBe(9003);
        expect(mailboxIdOf('diplomacy', { generalId: 1, nationId: 0 })).toBeNull();
    });

    it('서버가 가린 행(권한 < 3)은 hidden — 지운 서신 · 답한 제의와 가른다', () => {
        const items = toMailItems(env([
            row({ id: 50, msgType: 'diplomacy', src: who(8, 5), dest: nationOnly(3, '[세력]'), text: DIPLOMACY_MASK_TEXT, option: { invalid: true } }),
            row({ id: 51, msgType: 'diplomacy', src: who(1, 3), dest: nationOnly(5, '[원소]'), text: '삭제된 메시지입니다.', option: { invalid: true } }),
            row({ id: 52, msgType: 'diplomacy', src: who(8, 5), dest: nationOnly(3, '[세력]'), text: '불가침을 청합니다', option: { action: 'no_aggression', used: true, invalid: true } }),
            row({ id: 53, msgType: 'diplomacy', src: who(8, 5), dest: nationOnly(3, '[세력]'), text: '종전합시다', option: { action: 'stop_war' } }),
            row({ id: 54, msgType: 'diplomacy', src: who(8, 5), dest: nationOnly(3, '[세력]'), text: '모르는 제의', option: { action: 'che_모름' } }),
        ]), 'diplomacy', me);
        expect(items.map((i) => [i.id, i.hidden, i.html != null, i.proposal])).toEqual([
            [50, true, false, null],
            [51, false, false, null],
            [52, false, true, { kind: 'no_aggression', handled: true }],
            [53, false, true, { kind: 'stop_war', handled: false }],
            [54, false, true, null],
        ]);
        // 같은 글자라도 개인 서신은 가린 행이 아니다(서버도 외교 칸에만 가린다).
        const priv = toMailItems({ private: [row({ id: 55, text: DIPLOMACY_MASK_TEXT, option: { invalid: true } })], public: [], national: [], sequence: 0 }, 'private', me);
        expect(priv[0].hidden).toBe(false);
    });

    it('외교권자(flags 4)만 쓴다 · 받는 세력은 우리 세력 · 재야를 뺀 세력 서신함', () => {
        const list = { nation: [
            { mailbox: 9000, name: '재야', color: '#000000', general: [[9, '떠돌이', 0]] as [number, string, number][] },
            { mailbox: 9003, name: '[세력]', color: '#111', general: [[1, '나', 4], [7, '군주', 5]] as [number, string, number][] },
            { mailbox: 9006, name: '나라', color: '#222', general: [[8, '상대', 1]] as [number, string, number][] },
            { mailbox: 9005, name: '가라', color: '#333', general: [] as [number, string, number][] },
        ] };
        expect(toDiplomacyWrite(list, me)).toEqual({ canWrite: true, targets: [
            { nationId: 5, mailbox: 9005, name: '가라' }, { nationId: 6, mailbox: 9006, name: '나라' },
        ] });
        expect(toDiplomacyWrite(list, { generalId: 7, nationId: 3 }).canWrite).toBe(true);
        expect(toDiplomacyWrite(list, { generalId: 8, nationId: 6 }).canWrite).toBe(false);
        expect(toDiplomacyWrite(list, { generalId: 9, nationId: 0 }).canWrite).toBe(false);
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
