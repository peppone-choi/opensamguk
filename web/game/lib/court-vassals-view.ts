// 봉신 탭(P-K04) 보기 모델 — 보드 V31K8Vassals(목록 · 계약 상세 · 상납 이력). 순수 함수만(React · fetch 없음, D105 층).
// 저장 조건 PARTIAL 만 그린다(C5 #1373, K8 소비 답): 활성 · 달력 · 원군 응답 기한 · 설립 선택지는 서버 대기 칸으로 둔다.
// 이름 · 수치는 서버 값만 쓴다. 미확인 이름은 「이름을 아직 모릅니다」, 사람 여부 미확인은 칩 없이 서버 대기.
import type { CourtVassals, MonthlyTributeStatus, Resources, TributeReceipt, VassalAutonomy, VassalContract, VassalDiplomacyRight } from './api/court-vassals';

/** 탭이 고르는 모양. 계약 0개 확정은 READY 빈 목록일 때만이다(NOT_SEEDED · UNAVAILABLE 은 없음이 아니다). */
export type VassalsView =
    | { readonly kind: 'not-seeded' }
    | { readonly kind: 'unavailable' }
    | { readonly kind: 'empty' }
    | {
          readonly kind: 'contracts';
          /** 끝난 기록이 아닌 계약(활성 여부는 서버가 아직 판정하지 않는다 — activityStatus). */
          readonly current: readonly VassalContract[];
          /** endedTurn 이 있는 종료 기록. */
          readonly ended: readonly VassalContract[];
      };

export function vassalsView(v: CourtVassals): VassalsView {
    if (v.contractsStatus === 'NOT_SEEDED') return { kind: 'not-seeded' };
    if (v.contractsStatus === 'UNAVAILABLE' || v.status !== 'PARTIAL') return { kind: 'unavailable' };
    if (v.contracts.length === 0) return { kind: 'empty' };
    return {
        kind: 'contracts',
        current: v.contracts.filter((c) => c.endedTurn === null),
        ended: v.contracts.filter((c) => c.endedTurn !== null),
    };
}

export function vassalName(c: VassalContract): string {
    return c.vassalName ?? '이름을 아직 모릅니다';
}

/** 보드 AUTO · DIPLO 표 그대로(서버 enum 과 1:1). */
export const AUTONOMY_LABEL: Readonly<Record<VassalAutonomy, string>> = {
    COUNTY_POLICY: '현 방침',
    TAX_ALLOCATION: '세금 배분',
    GARRISON_COMMAND: '수비군 지휘',
};

export const DIPLOMACY_LABEL: Readonly<Record<VassalDiplomacyRight, string>> = {
    NONE: '없음',
    WITH_APPROVAL: '군주 승인 뒤',
    INDEPENDENT: '독자',
};

/** 보드 RES5 순서 — 상납 이력 표의 열. */
export const TRIBUTE_RESOURCES: readonly { readonly key: keyof Resources; readonly label: string }[] = [
    { key: 'money', label: '금' },
    { key: 'grain', label: '쌀' },
    { key: 'iron', label: '철' },
    { key: 'timber', label: '목재' },
    { key: 'horses', label: '말' },
];

export type TributeTone = 'moss' | 'rust' | 'neutral';

/**
 * 이번 달 상납 칩. 서버 monthlyTribute.status 를 그대로 옮긴다(규칙은 C5 문서: 미납 양수 → UNPAID, 청구 0 → ZERO_DUE, 그 밖 PAID).
 * UNAVAILABLE 은 null — 부르는 쪽이 서버 대기 칸으로 둔다. ZERO_DUE 는 「의무 없음」 판정이 아니다(obligationStatus UNAVAILABLE).
 */
export function monthlyTributeChip(status: MonthlyTributeStatus): { readonly label: string; readonly tone: TributeTone } | null {
    switch (status) {
        case 'PAID':
            return { label: '이번 달 완납', tone: 'moss' };
        case 'UNPAID':
            return { label: '이번 달 미납', tone: 'rust' };
        case 'ZERO_DUE':
            return { label: '이번 달 청구 없음', tone: 'neutral' };
        case 'NO_RECEIPT':
            return { label: '이번 달 아직', tone: 'neutral' };
        default:
            return null;
    }
}

/** 상납 이력 한 달의 상태 — 서버 monthlyTribute 와 같은 규칙(C5 문서)을 지난 달에도 쓴다. */
export function receiptStatus(r: TributeReceipt): 'PAID' | 'UNPAID' | 'ZERO_DUE' {
    const keys = TRIBUTE_RESOURCES.map((x) => x.key);
    if (keys.some((k) => r.unpaid[k] > 0)) return 'UNPAID';
    if (keys.every((k) => r.due[k] === 0)) return 'ZERO_DUE';
    return 'PAID';
}

/** 봉토 현 이름 — 지도 미리보기 이름(모르면 「어느 현」). */
export function fiefNames(c: VassalContract, countyName: (id: number) => string | null): readonly string[] {
    return c.fiefCountyIds.map((id) => countyName(id) ?? '어느 현');
}
