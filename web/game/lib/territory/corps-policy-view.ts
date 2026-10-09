// 영지 방침 칸의 군단 문맥(군단 화면에서 온 `?view=policy&scope=CORPS&orderId=…`) — 순수 뷰모델.
// 대상은 최신 READY 방침 읽기의 군단 줄에 있을 때만 표시하고, 권한은 그 줄의 settable · blocked 로만 판단한다.
// 대상이 없거나 못 읽었으면 그 사유를 쓰고, 다른 군단 · 현으로 대신하지 않는다. 편집 시트는 열지 않는다.
import type { Policies } from '../campaign-reads';
import { MISSING_REASON, commanderyPolicyRows, corpsPolicyRows, countyPolicyRows, type PolicyRow } from '../territory-view';
import type { TerritoryPolicyQuery } from './corps-policy-link';

export interface CorpsPolicyContext {
    /** 방침 칸이 처음 펼칠 탭 — 문맥이 있으면 늘 군단, 없으면 null(기본 탭). */
    readonly tab: 'CORPS' | null;
    /** 표시할 대상 줄 — 최신 READY 읽기에 그 줄이 있을 때만. */
    readonly targetId: string | null;
    readonly tone: 'info' | 'warn' | 'error';
    /** 안내 한 줄. null 이면 그리지 않는다. */
    readonly text: string | null;
}

export interface PolicyReadState {
    readonly loading: boolean;
    readonly error: string | null;
    readonly data: Policies | null;
}

export const CORPS_CONTEXT_TEXT = {
    invalid: '방침 대상 주소를 읽을 수 없습니다 — 군단 목록에서 방침을 바꿀 군단을 고르세요.',
    list: '방침을 바꿀 군단을 고르세요.',
    loading: '고른 군단의 방침을 불러오는 중입니다.',
    failed: '군단 방침을 불러오지 못해 고른 군단을 확인하지 못했습니다 — 다시 시도해 주세요.',
    notReady: '군단 방침을 지금 읽을 수 없어 고른 군단을 확인하지 못했습니다.',
    missing: '고른 군단을 찾을 수 없습니다 — 편성이 풀렸거나 내 군단이 아닙니다. 군단 목록에서 다시 고르세요.',
} as const;

const NO_CONTEXT: CorpsPolicyContext = { tab: null, targetId: null, tone: 'info', text: null };

export function corpsPolicyContext(query: TerritoryPolicyQuery, read: PolicyReadState): CorpsPolicyContext {
    if (query.kind === 'none') return NO_CONTEXT;
    if (query.kind === 'invalid') return { tab: 'CORPS', targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.invalid };
    if (query.kind === 'corpsList') return { tab: 'CORPS', targetId: null, tone: 'info', text: CORPS_CONTEXT_TEXT.list };
    if (read.error) return { tab: 'CORPS', targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.failed };
    if (!read.data) return { tab: 'CORPS', targetId: null, tone: 'info', text: CORPS_CONTEXT_TEXT.loading };
    if (read.data.status !== 'READY') return { tab: 'CORPS', targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.notReady };
    const row = corpsPolicyRows(read.data).find((r) => r.targetId === query.orderId);
    if (!row) return { tab: 'CORPS', targetId: null, tone: 'warn', text: CORPS_CONTEXT_TEXT.missing };
    if (!row.settable) {
        return { tab: 'CORPS', targetId: row.targetId, tone: 'warn', text: `${row.name} — 방침을 바꿀 수 없습니다: ${row.blocked?.reason?.trim() || MISSING_REASON}` };
    }
    return { tab: 'CORPS', targetId: row.targetId, tone: 'info', text: `${row.name} — 「바꾸기」를 눌러 방침을 고르세요.` };
}

/**
 * 편집을 열거나 보내기 직전 — 최신 READY 방침 읽기에서 같은 범위 · 같은 대상 줄을 다시 찾는다.
 * 줄이 사라졌거나 지금은 바꿀 수 없으면 null(옛 줄로 열거나 보내지 않는다).
 */
export function latestSettablePolicyRow(data: Policies | null, row: Pick<PolicyRow, 'scope' | 'targetId'>): PolicyRow | null {
    if (!data || data.status !== 'READY') return null;
    const rows = row.scope === 'COUNTY' ? countyPolicyRows(data) : row.scope === 'COMMANDERY' ? commanderyPolicyRows(data) : corpsPolicyRows(data);
    const hit = rows.find((r) => r.targetId === row.targetId);
    return hit?.settable ? hit : null;
}

export const STALE_POLICY_TARGET = '방침 대상이 바뀌었습니다 — 목록을 다시 확인한 뒤 골라 주세요.';
