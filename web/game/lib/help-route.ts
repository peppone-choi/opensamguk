// 도움말 보기를 주소에 싣는다 — 서랍 · 시트는 `?help=<값>`, 독립 페이지는 `/game/help?view=<값>`.
// 뒤로 가기가 보기를 되돌리고, 주소를 복사하면 같은 보기가 열린다. 값은 식별자라 화면에 쓰지 않는다.

export type HelpView =
    | { readonly kind: 'home' }
    | { readonly kind: 'browse' }
    | { readonly kind: 'search'; readonly q: string }
    | { readonly kind: 'input'; readonly inputId: string; readonly reason?: string }
    | { readonly kind: 'topic'; readonly topicId: string }
    | { readonly kind: 'failure'; readonly reason: string; readonly inputId?: string }
    | { readonly kind: 'start' };

export const HELP_PARAM = 'help';

const ID = /^[A-Za-z0-9_.-]{1,120}$/;

/** 주소 값 → 보기. 모르는 값 · 잘못된 식별자는 null(서랍을 열지 않는다). */
export function parseHelpView(value: string | null | undefined): HelpView | null {
    if (!value) return null;
    if (value === 'home') return { kind: 'home' };
    if (value === 'browse') return { kind: 'browse' };
    if (value === 'start') return { kind: 'start' };
    const i = value.indexOf(':');
    if (i < 0) return null;
    const head = value.slice(0, i);
    const rest = value.slice(i + 1);
    if (head === 'search') return { kind: 'search', q: rest.slice(0, 80) };
    if (head === 'input') {
        const [inputId, reason] = rest.split('!');
        if (!ID.test(inputId) || (reason !== undefined && !ID.test(reason))) return null;
        return reason ? { kind: 'input', inputId, reason } : { kind: 'input', inputId };
    }
    if (head === 'topic') return ID.test(rest) ? { kind: 'topic', topicId: rest } : null;
    if (head === 'failure') {
        const [reason, inputId] = rest.split('@');
        if (!ID.test(reason) || (inputId !== undefined && !ID.test(inputId))) return null;
        return inputId ? { kind: 'failure', reason, inputId } : { kind: 'failure', reason };
    }
    return null;
}

export function formatHelpView(view: HelpView): string {
    switch (view.kind) {
        case 'home':
        case 'browse':
        case 'start':
            return view.kind;
        case 'search':
            return `search:${view.q}`;
        case 'input':
            return `input:${view.inputId}${view.reason ? `!${view.reason}` : ''}`;
        case 'topic':
            return `topic:${view.topicId}`;
        case 'failure':
            return `failure:${view.reason}${view.inputId ? `@${view.inputId}` : ''}`;
    }
}
