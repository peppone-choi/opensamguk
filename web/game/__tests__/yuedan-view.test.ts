import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from 'vitest';
import type { PersonCard, Retinue } from '../lib/campaign-reads';
import { RENOWN_FALLING, RENOWN_RISING, departureRows, myRankIndex, pendingChip, plainLabel, reasonChip, stampLabel } from '../lib/yuedan-view';

test('경로 칩은 서버 RenownEventKind 라벨과 같다(오르는 넷 · 떨어지는 넷)', () => {
    const src = readFileSync(resolve(__dirname, '../../../logic/src/main/kotlin/opensamguk/logic/renown/RenownEvents.kt'), 'utf-8');
    const kinds = [...src.matchAll(/^\s+([A-Z_]+)\("\w+", "([^"]+)"\),?$/gm)].map((m) => ({ kind: m[1], label: m[2] }));
    expect(kinds).toHaveLength(8);
    const falling = new Set(['DEFEAT', 'BETRAYAL', 'MISRULE', 'DISPATCH_REFUSAL']);
    expect(kinds.filter((k) => !falling.has(k.kind)).map((k) => k.label)).toEqual([...RENOWN_RISING]);
    expect(kinds.filter((k) => falling.has(k.kind)).map((k) => k.label)).toEqual([...RENOWN_FALLING]);
});

test('도장 — 형식 밖이면 원문을 보이지 않고 null', () => {
    expect(stampLabel('0200-03')).toBe('200년 3월');
    expect(stampLabel('0200-13')).toBeNull();
    expect(stampLabel('2026-W14')).toBeNull();
    expect(stampLabel(null)).toBeNull();
});

test('사유 칩 — 부호 · 여러 건 ×n · 색, 한자 지명 글자는 쉬운 말로', () => {
    expect(reasonChip({ kind: 'WAR_MERIT', label: '전공', count: 2, amount: 6 })).toEqual({ text: '전공 +6 ×2', tone: 'moss' });
    expect(reasonChip({ kind: 'DISPATCH_REFUSAL', label: '발령 거절', count: 1, amount: -3 })).toEqual({ text: '발령 거절 -3', tone: 'rust' });
    expect(pendingChip({ kind: 'WAR_MERIT', label: '전공', stamp: '0200-04', sourceLabel: '縣 점령', amount: 4 }))
        .toEqual({ text: '전공 · 현 점령 +4', tone: 'moss' });
    expect(plainLabel('관할 縣 지표 상승')).toBe('관할 현 지표 상승');
});

test('이탈 판정 순서 — 서버 순서대로, 순서 없는 인물은 빠진다 · 내 순위 자리', () => {
    const p = (id: number, order: number | null): PersonCard => ({
        retainerId: id, generalId: null, name: `인물${id}`, picture: null, imageServer: 0, loyalty: 40, roleLabel: null, taskLabel: null,
        stats: null, cost: 10, aptitudes: null, bonds: [], departureOrder: order, locationCityId: null,
    });
    const r: Retinue = { status: 'READY', renown: 30, costSum: 36, overCapacity: true, people: [p(1, 2), p(2, null), p(3, 1)], units: [] };
    expect(departureRows(r).map((d) => [d.order, d.retainerId])).toEqual([[1, 3], [2, 1]]);
    expect(departureRows(null)).toEqual([]);
    const row = (generalId: number) => ({ rank: generalId, generalId, name: '', nationId: 0, nationName: null, nationColor: null, renown: 1 });
    expect(myRankIndex([row(3), row(7)], 7)).toBe(1);
    expect(myRankIndex([row(3)], 7)).toBe(-1);
});
