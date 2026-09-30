// 월단평(P-R04)의 보기 모델 — `/api/yuedan` + `/api/retinue`(이탈 순서)를 화면 줄로 바꾼다. React 없음.
//
// 오르는 · 떨어지는 경로는 서버 `RenownEventKind` 라벨 그대로다(설계서 P-R04 「서버 라벨로 맞춤」 —
// 옛 화면의 「결속 사건」 ≠ 서버 「결속」). 대조: __tests__/yuedan-view.test.ts 가 RenownEvents.kt 를 읽어 맞춘다.

import type { RenownPendingEvent, RenownReason, Retinue, YuedanRow } from './campaign-reads';

export const RENOWN_RISING = ['전공', '치적', '관직', '결속'] as const;
export const RENOWN_FALLING = ['패전', '배신', '실정', '발령 거절'] as const;

/** 도장 「0200-03」 → 「200년 3월」. 형식이 다르면 null — 화면은 원문 대신 「이번 달」 이라 적는다. */
export function stampLabel(stamp: string | null | undefined): string | null {
    if (!stamp) return null;
    const m = /^(\d+)-(\d{1,2})$/.exec(stamp);
    if (!m) return null;
    const month = Number(m[2]);
    return month >= 1 && month <= 12 ? `${Number(m[1])}년 ${month}월` : null;
}

/** 서버 원인 라벨의 한자 지명 글자를 쉬운 말로(「縣 점령」 → 「현 점령」). 다른 글자는 그대로. */
export function plainLabel(label: string): string {
    return label.replace(/\u7E23/g, '현'); // U+7E23 = 縣(한자 lint 가 소스 글자로 세지 않게 이스케이프)
}

export type ReasonTone = 'moss' | 'rust' | 'neutral';

export interface ReasonChip {
    readonly text: string;
    readonly tone: ReasonTone;
}

const signed = (n: number) => (n > 0 ? `+${n}` : String(n));
const toneOf = (n: number): ReasonTone => (n > 0 ? 'moss' : n < 0 ? 'rust' : 'neutral');

/** 순위 표의 이번 달 사유 칩 — 「전공 +6 ×2」(한 달에 종류당 여러 건이면 ×n). */
export function reasonChip(r: RenownReason): ReasonChip {
    const times = r.count > 1 ? ` ×${r.count}` : '';
    return { text: `${plainLabel(r.label)} ${signed(r.amount)}${times}`, tone: toneOf(r.amount) };
}

/** 다음 월단평에 반영될 내 사건 칩 — 「치적 · 직접 내정 행동 +2」. 원인은 본인에게만 온다. */
export function pendingChip(e: RenownPendingEvent): ReasonChip {
    const source = e.sourceLabel ? ` · ${plainLabel(e.sourceLabel)}` : '';
    return { text: `${plainLabel(e.label)}${source} ${signed(e.amount)}`, tone: toneOf(e.amount) };
}

export interface DepartureRow {
    readonly order: number;
    readonly retainerId: number;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly loyalty: number;
    readonly cost: number | null;
}

/** 이탈 판정 순서 — 서버가 매긴 departureOrder 그대로(충성이 낮은 인물부터). 순서가 없는 인물은 빠진다. */
export function departureRows(retinue: Retinue | null): DepartureRow[] {
    return (retinue?.people ?? [])
        .filter((p) => p.departureOrder != null)
        .sort((a, b) => (a.departureOrder ?? 0) - (b.departureOrder ?? 0))
        .map((p) => ({
            order: p.departureOrder!,
            retainerId: p.retainerId,
            name: p.name,
            picture: p.picture,
            imageServer: p.imageServer,
            loyalty: p.loyalty,
            cost: p.cost,
        }));
}

/** 순위 표에서 내 줄의 자리(「내 순위로」). 없으면 -1. */
export function myRankIndex(ranking: readonly YuedanRow[], meId: number | null | undefined): number {
    return meId == null ? -1 : ranking.findIndex((r) => r.generalId === meId);
}
