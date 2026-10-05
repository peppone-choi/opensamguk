// 지방 관직 보기 모델(K8-03) — 응답(lib/api/court-local-offices)과 지도 이름을 화면 줄로 옮긴다. 순수 함수만.
// 칩 · 실효 판정 글자는 보드 boards_v31_k8.py 의 ST · EVID 그대로다(설계서 P-K03 표).
// 이름은 짓지 않는다: 관할 이름은 치소 현의 지도 표시명(군국 · 주)에서, 모르면 「어느 군국 · 어느 주」.
import type {
    AppointmentOption,
    CourtLocalOffices,
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
 * 관직 화면 이름 — 사료 목록 data/curated/han/local-offices.json 의 id 그대로(시험이 목록과 맞는지 본다). 서버 officeName 은
 * 사료 표기(太守)라 화면에는 읽은 이름을 쓴다. 목록에 없는 id 는 서버가 준 이름을 그대로 쓴다(짓지 않는다).
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

export function officeLabel(officeId: string, serverName: string | null = null): string {
    return OFFICE_LABEL[officeId] ?? serverName ?? '어느 관직';
}

/** 지도 미리보기에서 얻는 현 이름들. 못 받았으면 null — 값을 짓지 않는다. */
export interface CountyPlace {
    /** 현 이름(지도 name). */
    readonly name: string;
    /** 상위 군 · 국 · 윤 표시명. */
    readonly commandery: string | null;
    /** 주(州) 표시명. */
    readonly region: string | null;
}
export type PlaceOf = (countyId: number) => CountyPlace | null;

export type JurisdictionKind = 'ZHOU' | 'COMMANDERY';
export const jurisdictionKind = (jurisdictionId: string): JurisdictionKind => (jurisdictionId.startsWith('zhou:') ? 'ZHOU' : 'COMMANDERY');

/** 관할 이름 — 치소 현이 속한 주 · 군국의 지도 표시명. */
export function jurisdictionLabel(jurisdictionId: string, seatCountyId: number, place: PlaceOf): string {
    const seat = place(seatCountyId);
    if (jurisdictionKind(jurisdictionId) === 'ZHOU') return seat?.region ?? '어느 주';
    return seat?.commandery ?? '어느 군국';
}

export const phaseText = (p: Phase): string => `${p.year}년 ${p.month}월 ${['초', '중', '하'][p.phase - 1]}순`;

export interface TenureRow {
    readonly tenureId: string;
    readonly depth: 0 | 1;
    readonly jurisdiction: string;
    /** 「치소 ○○」 — 치소 현 이름을 모르면 null. */
    readonly seat: string | null;
    readonly region: string | null;
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
    /** 같은 응답의 다른 줄(재임 · 선택지)에서 같은 관할 · 후보를 찾아 얻은 이름. 못 찾으면 null — 계약 빈칸이라 서버 대기로 그린다. */
    readonly jurisdiction: string | null;
    readonly candidate: string | null;
    readonly office: string;
    readonly chip: { readonly label: string; readonly tone: ChipTone };
    readonly due: string;
    readonly offer: PendingOffer;
}

export type LocalOfficesView =
    | { readonly kind: 'unavailable' }
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

function tenureRow(t: LocalTenure, place: PlaceOf): TenureRow {
    const seat = place(t.seatCountyId);
    return {
        tenureId: t.tenureId,
        depth: jurisdictionKind(t.jurisdictionId) === 'ZHOU' ? 0 : 1,
        jurisdiction: jurisdictionLabel(t.jurisdictionId, t.seatCountyId, place),
        seat: seat ? `치소 ${seat.name}` : null,
        region: seat?.region ?? null,
        office: officeLabel(t.officeId, t.officeName),
        holder: t.holderName,
        chip: TENURE_CHIP[t.state],
        effective: effectiveText(t),
        tenure: t,
    };
}

function optionRow(o: AppointmentOption, place: PlaceOf, i: number): OptionRow {
    return {
        key: `${o.jurisdictionId}|${o.officeId}|${o.candidateId}|${i}`,
        jurisdiction: jurisdictionLabel(o.jurisdictionId, o.seatCountyId, place),
        office: officeLabel(o.officeId),
        candidate: o.candidateName,
        available: o.available,
        reason: o.blocked?.reason ?? null,
    };
}

/**
 * 보낸 제안에는 치소 현 · 후보 이름이 없다(계약 빈칸). 같은 응답의 재임 · 선택지에서 같은 관할 ID 의 치소, 같은 후보 ID 의 이름을
 * 찾으면 쓰고, 못 찾으면 null 로 둔다 — 사료 ID 의 꼬리(한자 이름)를 화면 이름으로 쓰지 않는다.
 */
function offerRow(o: PendingOffer, data: CourtLocalOffices, place: PlaceOf): OfferRow {
    const seatCountyId =
        data.localOffices.find((t) => t.jurisdictionId === o.jurisdictionId)?.seatCountyId ??
        data.appointmentOptions.find((a) => a.jurisdictionId === o.jurisdictionId)?.seatCountyId ??
        null;
    const jurisdiction = seatCountyId === null ? null : jurisdictionLabel(o.jurisdictionId, seatCountyId, place);
    const candidate = data.appointmentOptions.find((a) => a.candidateId === o.candidateId)?.candidateName ?? null;
    return {
        offerId: o.offerId,
        jurisdiction: jurisdiction === '어느 주' || jurisdiction === '어느 군국' ? null : jurisdiction,
        candidate,
        office: officeLabel(o.officeId),
        chip: OFFER_STATE_CHIP[o.status],
        due: phaseText(o.dueAt),
        offer: o,
    };
}

/** 주 → 그 주의 군국 순(치소 현의 주 이름으로 묶는다), 같은 층은 이름 순. */
function order(a: TenureRow, b: TenureRow): number {
    const ra = a.depth === 0 ? a.jurisdiction : a.region ?? '';
    const rb = b.depth === 0 ? b.jurisdiction : b.region ?? '';
    return ra.localeCompare(rb, 'ko') || a.depth - b.depth || a.jurisdiction.localeCompare(b.jurisdiction, 'ko') || a.tenureId.localeCompare(b.tenureId);
}

export function localOfficesView(data: CourtLocalOffices, place: PlaceOf): LocalOfficesView {
    if (data.status === 'UNAVAILABLE') return { kind: 'unavailable' };
    const options = data.appointmentOptions.map((o, i) => optionRow(o, place, i));
    const offers = data.pendingOffers.map((o) => offerRow(o, data, place));
    if (data.localOffices.length === 0) return { kind: 'empty', options, offers };
    const rows = data.localOffices.map((t) => tenureRow(t, place)).sort(order);
    const count = (s: TenureState) => data.localOffices.filter((t) => t.state === s).length;
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
