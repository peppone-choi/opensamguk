// 명령 흐름 URL — `?do=<inputId>&slot=<1-12>&target=<kind>:<id>`(설계서 §2.1).
// 새로고침 · 뒤로 가기 · 공유에 흐름이 남는다. 모르는 값은 버린다(지어 채우지 않는다).
import { flowCommand } from './catalog';

export type TargetKind = 'province' | 'commandery' | 'county' | 'general' | 'corps';
export const TARGET_KINDS: readonly TargetKind[] = ['province', 'commandery', 'county', 'general', 'corps'];

export interface FlowTarget {
    readonly kind: TargetKind;
    readonly id: string;
}

export interface FlowQuery {
    /** 흐름이 열려 있는가 — `do` · `slot` · `target` 중 하나라도 있으면 연다. */
    readonly open: boolean;
    readonly inputId: string | null;
    /** 0–11. 없으면 null(여는 쪽이 다음 빈 순을 고른다). */
    readonly slot: number | null;
    readonly target: FlowTarget | null;
}

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export function parseFlowQuery(params: URLSearchParams): FlowQuery {
    const rawDo = params.get('do');
    const inputId = rawDo && flowCommand(rawDo) ? rawDo : null;
    const rawSlot = params.get('slot');
    const n = rawSlot != null && /^\d{1,2}$/.test(rawSlot) ? Number(rawSlot) : NaN;
    const slot = n >= 1 && n <= 12 ? n - 1 : null;
    const target = parseTarget(params.get('target'));
    const open = params.has('do') || params.has('slot') || params.has('target');
    return { open, inputId, slot, target };
}

export function parseTarget(raw: string | null): FlowTarget | null {
    if (!raw) return null;
    const i = raw.indexOf(':');
    if (i <= 0) return null;
    const kind = raw.slice(0, i) as TargetKind;
    const id = raw.slice(i + 1);
    return TARGET_KINDS.includes(kind) && ID_RE.test(id) ? { kind, id } : null;
}

/** 지금 주소의 다른 쿼리는 두고 흐름 키만 바꾼다. 닫으면(null) 흐름 키를 지운다. */
export function withFlowQuery(params: URLSearchParams, flow: { inputId: string | null; slot: number; target?: FlowTarget | null } | null): URLSearchParams {
    const next = new URLSearchParams(params);
    next.delete('do');
    next.delete('slot');
    next.delete('target');
    if (!flow) return next;
    if (flow.inputId) next.set('do', flow.inputId);
    next.set('slot', String(flow.slot + 1));
    if (flow.target) next.set('target', `${flow.target.kind}:${flow.target.id}`);
    return next;
}
