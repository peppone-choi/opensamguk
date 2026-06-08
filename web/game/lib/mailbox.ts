export const MAILBOX_PUBLIC = 9999;
export const MAILBOX_NATIONAL_BASE = 9000;

export type MailboxScope = 'private' | 'national' | 'public';

export interface MailboxIdentity {
    generalId: number | null;
    nationId: number;
}

export function mailboxIdForScope(scope: MailboxScope, identity: MailboxIdentity): number | null {
    if (scope === 'public') return MAILBOX_PUBLIC;
    if (scope === 'national') {
        return identity.nationId > 0 ? MAILBOX_NATIONAL_BASE + identity.nationId : null;
    }
    return identity.generalId != null && identity.generalId > 0 ? identity.generalId : null;
}
