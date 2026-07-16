import { describe, expect, it } from 'vitest';
import {
    MAILBOX_NATIONAL_BASE,
    MAILBOX_PUBLIC,
    MESSAGE_DELETABLE_WINDOW_MS,
    isMessageDeletable,
    mailboxIdForScope,
} from '../lib/mailbox';
import type { MailboxMessage } from '../types/game';

describe('mailboxIdForScope', () => {
    it('uses the caller general id for private mailbox reads', () => {
        expect(mailboxIdForScope('private', { generalId: 42, nationId: 7 })).toBe(42);
    });

    it('uses legacy national and public mailbox ids', () => {
        expect(mailboxIdForScope('national', { generalId: 42, nationId: 7 })).toBe(MAILBOX_NATIONAL_BASE + 7);
        expect(mailboxIdForScope('public', { generalId: null, nationId: 0 })).toBe(MAILBOX_PUBLIC);
    });

    it('does not fall back to fabricated id 1 when identity is missing', () => {
        expect(mailboxIdForScope('private', { generalId: null, nationId: 7 })).toBeNull();
        expect(mailboxIdForScope('national', { generalId: 42, nationId: 0 })).toBeNull();
    });
});

// legacy MessagePlate.vue testDeletable()(197-219행) 게이팅 미러.
describe('isMessageDeletable', () => {
    const NOW = Date.UTC(2026, 6, 16, 12, 0, 0); // 고정 기준시각(테스트 결정성)
    const base = (over: Partial<MailboxMessage> = {}): MailboxMessage => ({
        id: 55,
        mailbox: 10,
        type: 'private',
        src: 10, // 발신자 = 본인(generalId 10)
        dest: 20,
        time: new Date(NOW - 60_000).toISOString(), // 1분 전 발송 → 5분 창 이내
        validUntil: new Date(NOW + 3_600_000).toISOString(),
        message: '',
        text: '안녕하세요',
        srcTarget: null,
        destTarget: null,
        option: {},
        ...over,
    });

    it('발신자 본인이 보낸 5분 이내 메시지는 삭제 가능', () => {
        expect(isMessageDeletable(base(), 10, NOW)).toBe(true);
    });

    it('발신자가 본인이 아니면 삭제 불가', () => {
        expect(isMessageDeletable(base({ src: 99 }), 10, NOW)).toBe(false);
    });

    it('id가 없는 메시지는 삭제 API에 넘길 값이 없으므로 버튼 미노출', () => {
        expect(isMessageDeletable(base({ id: null }), 10, NOW)).toBe(false);
    });

    it('장수 정보가 없으면(generalId null) 삭제 불가', () => {
        expect(isMessageDeletable(base(), null, NOW)).toBe(false);
    });

    it('수락/거절(action) 메시지는 삭제 불가', () => {
        expect(isMessageDeletable(base({ option: { action: 'che_불가침수락' } }), 10, NOW)).toBe(false);
    });

    it('이미 무효(invalid) 처리된 메시지는 삭제 불가', () => {
        expect(isMessageDeletable(base({ option: { invalid: true } }), 10, NOW)).toBe(false);
    });

    it('명시적 non-deletable(option.deletable=false)은 삭제 불가', () => {
        expect(isMessageDeletable(base({ option: { deletable: false } }), 10, NOW)).toBe(false);
    });

    it('발송 5분(경계 초과)이 지나면 삭제 불가', () => {
        const stale = base({ time: new Date(NOW - MESSAGE_DELETABLE_WINDOW_MS - 1).toISOString() });
        expect(isMessageDeletable(stale, 10, NOW)).toBe(false);
    });

    // 경계 잠금 — 레거시 testDeletable의 `timeDiff <= 0 → false`(정확히 5분 = 삭제 불가)와 일치.
    it('정확히 5분 경계(now - sentAt === WINDOW)는 삭제 불가', () => {
        const exact = base({ time: new Date(NOW - MESSAGE_DELETABLE_WINDOW_MS).toISOString() });
        expect(isMessageDeletable(exact, 10, NOW)).toBe(false);
    });

    it('경계 1ms 이내(now - sentAt === WINDOW - 1)는 삭제 가능', () => {
        const justInside = base({ time: new Date(NOW - (MESSAGE_DELETABLE_WINDOW_MS - 1)).toISOString() });
        expect(isMessageDeletable(justInside, 10, NOW)).toBe(true);
    });
});
