import { describe, expect, it } from 'vitest';
import { MAILBOX_NATIONAL_BASE, MAILBOX_PUBLIC, mailboxIdForScope } from '../lib/mailbox';

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
