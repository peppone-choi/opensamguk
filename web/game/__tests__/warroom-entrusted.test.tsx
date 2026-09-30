import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import { EntrustedTasks } from '../components/warroom/EntrustedTasks';
import type { Policies, Posts, Works } from '../lib/campaign-reads';
import type { DeployOptions, DispatchPendingResponse } from '../lib/types';
import { deployValue, entrustedCells } from '../lib/war-room-view';

const deploy = (order: DeployOptions['order']): DeployOptions => ({ available: true, maxReservedTurns: 12, bugoks: [], destinations: [], order });
const phase = { year: 200, month: 3, phase: 1 };

test('출병 — 받기 전 「—」(「없음」 아님), 없음 · 행군 중 · 멈춤 글자, 모르는 코드는 원문 대신', () => {
    expect(deployValue(undefined)).toBe('—');
    expect(deployValue(null)).toBe('—');
    expect(deployValue(deploy(null))).toBe('없음');
    expect(deployValue(deploy({ orderId: 'o1', destinationProvinceId: 'p1', stop: null }))).toBe('행군 중');
    expect(deployValue(deploy({ orderId: 'o1', destinationProvinceId: 'p1', stop: 'ENCOUNTER' }))).toBe('조우로 멈춤');
    expect(deployValue(deploy({ orderId: 'o1', destinationProvinceId: 'p1', stop: 'NEW_STOP' }))).toBe('멈춤(사유 준비 중)');
});

test('6칸 — 수 세기, 실패 · 준비 안 됨은 그 칸만 「—」, 내 응답 필요', () => {
    const posts = { status: 'READY', posts: [], cards: [
        { cardId: 1, generalId: 1, name: 'a', relation: 'L', provinceId: null, placeable: true, blocked: null, active: { post: 'M', postLabel: '현령', target: {}, state: 'ARRIVED' }, pending: null },
        { cardId: 2, generalId: 2, name: 'b', relation: 'L', provinceId: null, placeable: true, blocked: null, active: null, pending: null },
    ] } as Posts;
    const works = { status: 'UNAVAILABLE', counties: [] } as Works;
    const policies = { status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, counties: [], corps: [],
        commanderies: [{ commanderyId: 'c', name: null, countyIds: [], active: { policy: 'FARM', label: '농업' }, pending: null, settable: true, blocked: null }] } as Policies;
    const dispatches: DispatchPendingResponse = { result: true, dispatches: [
        { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 1, issuedAt: phase, dueAt: phase, status: 'PENDING' },
        { dispatchId: 'd2', issuerId: 7, targetId: 3, countyId: 2, issuedAt: phase, dueAt: phase, status: 'PENDING' },
        { dispatchId: 'd3', issuerId: 7, targetId: 4, countyId: 3, issuedAt: phase, dueAt: phase, status: 'ACCEPTED' },
    ] };
    const cells = entrustedCells({ deploy: undefined, posts, policies, works, hand: null, dispatches, generalId: 7 });
    expect(cells.map((c) => [c.label, c.value, c.alert])).toEqual([
        ['출병', '—', null], ['배치', '1', null], ['방침', '1', null], ['공사', '—', null], ['계책 손패', '—', null], ['발령 진행', '2', 1],
    ]);
    render(<EntrustedTasks cells={cells} hrefOf={(k) => `/game/pep/${k}`} />);
    const links = within(screen.getByRole('navigation', { name: '맡겨 둔 일' })).getAllByRole('link');
    expect(links).toHaveLength(6);
    expect(links[5]).toHaveTextContent('내 응답 필요 1');
    expect(links[0]).toHaveTextContent('출병—');
    expect(document.body).not.toHaveTextContent('출병 없음');
});
