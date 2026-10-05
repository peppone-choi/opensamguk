// 지방 관직 보기 모델(K8-03) — 응답(lib/api/court-local-offices)을 화면 줄로 옮긴다. 순수 함수만.
// 칩 · 실효 판정 글자는 보드 boards_v31_k8.py 의 ST · EVID 그대로다(설계서 P-K03 표).
// 이름은 짓지 않는다(D124 C5 ACK): 관할 이름은 서버 jurisdictionName(같은 응답 안에서만 찾는다) — 지도 표시명으로 관할을 짐작해
// 잇지 않는다. 치소 현은 서버 seatCountyName, 없으면 같은 현 id 의 지도 이름. 모르면 「어느 …」.
import type {
    AppointmentOption,
    CourtLocalOffices,
    LocalOfficesReason,
    LocalTenure,
    OfficeEvidence,
    OfficeOfferState,
    PendingOffer,
    Phase,
    TenureState,
} from './api/court-local-offices';

export type ChipTone = 'moss' | 'rust' | 'info';

export const TENURE_CHIP: Readonly<Record<TenureState, { readonly label: string; readonly tone: ChipTone }>> = {
    EFFECTIVE: { label: '실권 있음', tone: 'moss' },
    NOMINAL: { label: '명목', tone: 'rust' },
    AWAITING_ARRIVAL: { label: '부임 전', tone: 'info' },
    PENDING_ACCEPTANCE: { label: '수락 대기', tone: 'info' },
};

export const OFFER_STATE_CHIP: Readonly<Record<OfficeOfferState, { readonly label: string; readonly tone: ChipTone }>> = {
    PENDING: { label: '수락 대기', tone: 'info' },
    ACCEPTED: { label: '받아들임', tone: 'moss' },
    REFUSED: { label: '거절함', tone: 'rust' },
};

/** 실효 판정 — 모두 맞아야 실권이 있다(보드 EVID 순서). */
export const EVIDENCE_TEXT: readonly (readonly [OfficeEvidence, string])[] = [
    ['LIVING_CLAIM', '앉은 사람이 살아 있다'],
    ['ACCEPTED_TENURE', '임명을 받아들였다'],
    ['ASSUMED_SEAT', '부임했다'],
    ['SEAT_OWNED', '치소 현을 가졌다'],
    ['HOLDER_AT_SEAT', '앉은 사람이 치소에 있다'],
    ['COUNTY_MAJORITY', '관할 현을 문턱 넘게 가졌다'],
    ['WAREHOUSE_CONNECTION', '치소 창고가 보급망에 이어졌다'],
    ['LOCAL_MAGISTRATE_OR_GARRISON', '현령이 앉았거나 군대가 머문다'],
];

/**
 * 관직 화면 이름 — 서버 officeLabel 이 정본이다(ACK). 서버가 null 이면 사료 목록 data/curated/han/local-offices.json 의 id 에 맞춘
 * 읽은 이름(시험이 목록과 맞는지 본다), 목록에도 없으면 서버 사료 표기를 그대로 쓴다(짓지 않는다).
 */
export const OFFICE_LABEL: Readonly<Record<string, string>> = {
    'office.provincial-inspector': '자사',
    'office.provincial-governor': '주목',
    'office.commandery-prefect': '태수',
    'office.principality-chancellor': '국상',
    'office.county-magistrate': '현령',
    'office.county-chief': '현장',
    'office.marquisate-chancellor': '후국상',
};

export function officeLabel(officeId: string, serverLabel: string | null = null, serverName: string | null = null): string {
    return serverLabel ?? OFFICE_LABEL[officeId] ?? serverName ?? '어느 관직';
}

/** 지도 미리보기의 현 이름(같은 현 id). 못 받았으면 null — 값을 짓지 않는다. */
export type CountyNameOf = (countyId: number) => string | null;

export type JurisdictionKind = 'ZHOU' | 'COMMANDERY';
export const jurisdictionKind = (jurisdictionId: string): JurisdictionKind => (jurisdictionId.startsWith('zhou:') ? 'ZHOU' : 'COMMANDERY');
const unknownJurisdiction = (jurisdictionId: string) => (jurisdictionKind(jurisdictionId) === 'ZHOU' ? '어느 주' : '어느 군국');

/** 같은 응답 안의 재임에서 같은 관할 ID 의 서버 한글 이름을 찾는다. */
function jurisdictionNameIn(data: CourtLocalOffices, jurisdictionId: string): string | null {
    return data.localOffices?.find((t) => t.jurisdictionId === jurisdictionId && t.jurisdictionName !== null)?.jurisdictionName ?? null;
}

export const phaseText = (p: Phase): string => `${p.year}년 ${p.month}월 ${['초', '중', '하'][p.phase - 1]}순`;

export interface TenureRow {
    readonly tenureId: string;
    readonly depth: 0 | 1;
    readonly jurisdiction: string;
    /** 「치소 ○○」 — 치소 현 이름을 모르면 null. */
    readonly seat: string | null;
    readonly office: string;
    readonly holder: string;
    readonly chip: { readonly label: string; readonly tone: ChipTone };
    /** 실효 현 — 실효면 「n곳」, 아니면 「—」(명목은 「0곳」). */
    readonly effective: string;
    readonly tenure: LocalTenure;
}

export interface OptionRow {
    readonly key: string;
    readonly jurisdiction: string;
    readonly office: string;
    readonly candidate: string;
    readonly available: boolean;
    readonly reason: string | null;
}

export interface OfferRow {
    readonly offerId: string;
    /** 같은 응답 안에서 찾은 이름. 못 찾으면 null — 계약 빈칸이라 서버 대기로 그린다. */
    readonly jurisdiction: string | null;
    readonly candidate: string | null;
    readonly office: string;
    readonly chip: { readonly label: string; readonly tone: ChipTone };
    readonly due: string;
    readonly offer: PendingOffer;
}

/**
 * - not-seeded  서버는 답했지만 재임 원천이 아직 없다(「관직 0개」가 아님).
 * - snapshot    열린 재임이 있지만 실권 판정에 필요한 관할 정보를 서버가 아직 셈하지 못한다(서버 기능 대기).
 * - unavailable 월드 · 저장 값 · 재야 등으로 셈하지 못했다.
 * - empty       확인된 빈 결과(이 세력의 앉은 지방 관직 0개).
 */
export type LocalOfficesView =
    | { readonly kind: 'not-seeded' }
    | { readonly kind: 'snapshot' }
    | { readonly kind: 'unavailable'; readonly reason: LocalOfficesReason | null }
    | { readonly kind: 'empty'; readonly options: readonly OptionRow[]; readonly offers: readonly OfferRow[] }
    | {
          readonly kind: 'offices';
          readonly rows: readonly TenureRow[];
          readonly counts: { readonly pending: number; readonly nominal: number; readonly awaiting: number };
          readonly options: readonly OptionRow[];
          readonly offers: readonly OfferRow[];
      };

function effectiveText(t: LocalTenure): string {
    if (t.state === 'EFFECTIVE') return `${t.actualCountyIds.length}곳`;
    return t.state === 'NOMINAL' ? '0곳' : '—';
}

function tenureRow(t: LocalTenure, countyName: CountyNameOf): TenureRow {
    const seat = t.seatCountyName ?? countyName(t.seatCountyId);
    return {
        tenureId: t.tenureId,
        depth: jurisdictionKind(t.jurisdictionId) === 'ZHOU' ? 0 : 1,
        jurisdiction: t.jurisdictionName ?? unknownJurisdiction(t.jurisdictionId),
        seat: seat ? `치소 ${seat}` : null,
        office: officeLabel(t.officeId, t.officeLabel, t.officeName),
        holder: t.holderName,
        chip: TENURE_CHIP[t.state],
        effective: effectiveText(t),
        tenure: t,
    };
}

function optionRow(o: AppointmentOption, data: CourtLocalOffices, i: number): OptionRow {
    return {
        key: `${o.jurisdictionId}|${o.officeId}|${o.candidateId}|${i}`,
        jurisdiction: jurisdictionNameIn(data, o.jurisdictionId) ?? unknownJurisdiction(o.jurisdictionId),
        office: officeLabel(o.officeId),
        candidate: o.candidateName,
        available: o.available,
        reason: o.blocked?.reason ?? null,
    };
}

/** 보낸 제안에는 후보 · 관할 이름이 없다(계약 빈칸) — 같은 응답의 재임 · 선택지에서 찾고, 못 찾으면 null. */
function offerRow(o: PendingOffer, data: CourtLocalOffices): OfferRow {
    return {
        offerId: o.offerId,
        jurisdiction: jurisdictionNameIn(data, o.jurisdictionId),
        candidate: data.appointmentOptions?.find((a) => a.candidateId === o.candidateId)?.candidateName ?? null,
        office: officeLabel(o.officeId),
        chip: OFFER_STATE_CHIP[o.status],
        due: phaseText(o.dueAt),
        offer: o,
    };
}

/** 주 먼저, 같은 층은 이름 순. */
function order(a: TenureRow, b: TenureRow): number {
    return a.depth - b.depth || a.jurisdiction.localeCompare(b.jurisdiction, 'ko') || a.tenureId.localeCompare(b.tenureId);
}

export function localOfficesView(data: CourtLocalOffices, countyName: CountyNameOf): LocalOfficesView {
    if (data.status === 'NOT_SEEDED') return { kind: 'not-seeded' };
    if (data.status === 'UNAVAILABLE') {
        return data.reason === 'JURISDICTION_SNAPSHOT_UNAVAILABLE' ? { kind: 'snapshot' } : { kind: 'unavailable', reason: data.reason };
    }
    const tenures = data.localOffices ?? [];
    const options = (data.appointmentOptions ?? []).map((o, i) => optionRow(o, data, i));
    const offers = (data.pendingOffers ?? []).map((o) => offerRow(o, data));
    if (tenures.length === 0) return { kind: 'empty', options, offers };
    const rows = tenures.map((t) => tenureRow(t, countyName)).sort(order);
    const count = (s: TenureState) => tenures.filter((t) => t.state === s).length;
    return {
        kind: 'offices',
        rows,
        counts: { pending: count('PENDING_ACCEPTANCE'), nominal: count('NOMINAL'), awaiting: count('AWAITING_ARRIVAL') },
        options,
        offers,
    };
}

/** 고른 자리의 실효 판정 줄 — 부족한 근거(missing)면 ok=false. */
export function evidenceRows(t: LocalTenure): readonly { readonly code: OfficeEvidence; readonly text: string; readonly ok: boolean }[] {
    return EVIDENCE_TEXT.map(([code, text]) => ({ code, text, ok: !t.missing.includes(code) }));
}
