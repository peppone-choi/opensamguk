// 시야 · 첩보(P-C06) 모델 — `/api/visibility` + `/api/scout-options`. K6 설계서 §3.6, 시야 계약 §4.
// - 단계: 다 보임(FULL) · 첩보(INTEL, 「N순 전」 — 만료 없음) · 안 보임(FOG). 색만으로 가르지 않는다(글자 칩).
// - 누출 금지: 역정보는 첩보 스냅숏에 섞여 오고 표식이 없다. 이 화면은 「가짜 · 역정보 · 의심」을 어디에도 쓰지 않는다.
// - 시야 출처(sources) · 잘못된 출처 기록 수는 서버가 아직 내보내지 않는다(서버 대기).
import type { ScoutOptions, Visibility, VisionTier } from '../campaign-reads';

export const TIER_LABEL: Readonly<Record<VisionTier, string>> = { FULL: '다 보임', INTEL: '첩보', FOG: '안 보임' };
export const TIER_ORDER: readonly VisionTier[] = ['INTEL', 'FOG', 'FULL'];

export interface IntelRow {
    readonly no: number;
    /** 첩보 대상 인자(commanderyId). */
    readonly id: string;
    readonly name: string;
    readonly tier: VisionTier;
    readonly ageTurns: number | null;
    /** 첩보 옵션 — 이 군이 첩보 후보면 가능 여부 · 사유, 후보가 아니면 null(단추를 그리지 않는다). */
    readonly scout: { readonly available: boolean; readonly code: string | null; readonly reason: string | null } | null;
}

export type IntelView =
    | { readonly state: 'unreadable'; readonly status: string }
    | { readonly state: 'ready'; readonly groups: readonly { readonly tier: VisionTier; readonly rows: readonly IntelRow[] }[]; readonly scoutBlocked: { readonly code: string | null; readonly reason: string | null } | null };

export function toIntelView(vision: Visibility, scout: ScoutOptions | null): IntelView {
    if (vision.status !== 'READY') return { state: 'unreadable', status: vision.status };
    const scoutReady = scout?.status === 'READY' ? scout : null;
    const byNo = new Map((scoutReady?.options ?? []).map((o) => [o.no, o]));
    const rows: IntelRow[] = (vision.commanderies ?? []).map((c) => {
        const o = byNo.get(c.no);
        return {
            no: c.no, id: c.id, name: c.name, tier: c.tier, ageTurns: c.ageTurns ?? null,
            scout: o ? { available: o.available, code: o.code ?? null, reason: o.reason ?? null } : null,
        };
    });
    const groups = TIER_ORDER.map((tier) => ({
        tier,
        rows: rows.filter((r) => r.tier === tier).sort((a, b) => (b.ageTurns ?? -1) - (a.ageTurns ?? -1) || a.name.localeCompare(b.name, 'ko')),
    })).filter((g) => g.rows.length > 0);
    const blocked = scoutReady && scoutReady.available === false ? { code: scoutReady.code ?? null, reason: scoutReady.reason ?? null } : null;
    return { state: 'ready', groups, scoutBlocked: blocked };
}

/** 첩보 행 둘째 줄 — 일반 문구만(역정보 표식 없음). */
export function tierLine(row: IntelRow): string {
    if (row.tier === 'INTEL') return row.ageTurns != null ? `${row.ageTurns}순 전 첩보 · 다시 첩보하면 갱신됩니다` : '첩보 · 다시 첩보하면 갱신됩니다';
    if (row.tier === 'FULL') return '지금 보입니다';
    return '보이지 않습니다';
}
