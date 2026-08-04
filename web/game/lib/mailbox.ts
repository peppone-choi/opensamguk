import type { MailboxMessage } from '../types/game';

export const MAILBOX_PUBLIC = 9999;
export const MAILBOX_NATIONAL_BASE = 9000;

// 서신 삭제 버튼 노출 5분 창(밀리초) — legacy MessagePlate.vue testDeletable() addMinutes(time, 5).
export const MESSAGE_DELETABLE_WINDOW_MS = 5 * 60 * 1000;

export type MailboxScope = 'private' | 'national' | 'public' | 'diplomacy';

export interface MailboxIdentity {
    generalId: number | null;
    nationId: number;
}

export function mailboxIdForScope(scope: MailboxScope, identity: MailboxIdentity): number | null {
    if (scope === 'public') return MAILBOX_PUBLIC;
    if (scope === 'national' || scope === 'diplomacy') {
        return identity.nationId > 0 ? MAILBOX_NATIONAL_BASE + identity.nationId : null;
    }
    return identity.generalId != null && identity.generalId > 0 ? identity.generalId : null;
}

// 서신 삭제 버튼 노출 조건 — legacy hwe/ts/components/MessagePlate.vue testDeletable()(197-219행)와
// 동일 게이팅: 발신자 본인 + 수락/거절(action) 아님 + 무효(invalid) 아님 + 명시적 non-deletable 아님 +
// 발송 5분 이내. (엔진 MessageHandler.handleDelete가 동일 규칙으로 최종 재검증 — FE는 버튼 노출만
// 게이팅하고 최종 승인/거부는 서버가 한다. legacy의 `deleted` prop은 재조회 시 목록에서 사라지므로
// 상응 상태가 없어 생략한다.)
export function isMessageDeletable(
    msg: MailboxMessage,
    generalId: number | null,
    now: number = Date.now(),
): boolean {
    if (generalId == null) return false;
    if (msg.id == null) return false; // 삭제 API에 넘길 id가 없으면 버튼 자체를 노출하지 않는다
    const option: Record<string, unknown> = msg.option ?? {};
    if (option.action) return false; // 수락/거절 대상(외교) 메시지는 삭제 불가
    if (msg.src !== generalId) return false; // 발신자 본인만 삭제 가능
    if (option.invalid) return false; // 이미 무효 처리된 메시지
    if (option.deletable === false) return false; // 명시적 non-deletable (기본값 true)
    const sentAt = new Date(msg.time).getTime();
    if (Number.isNaN(sentAt)) return false;
    return now - sentAt < MESSAGE_DELETABLE_WINDOW_MS; // 발송 5분 이내만
}
