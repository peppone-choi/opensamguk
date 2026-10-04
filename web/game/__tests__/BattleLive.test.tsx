// 실시간 전투 화면(P-C05) — 판은 흉내(캔버스 없음). 여럿 고르기 · 「내 부곡 전부」 → allMine 명령 · 하나만 → sourceKeys ·
// 고르지 않으면 · 집결점이 섞이면 보내지 않고 사유 · 영수증 알림 · 서버 대기 표지(#1335).
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { BattleLive } from '../components/battle/BattleLive';
import { toLiveView } from '../lib/battle/live-view';
import { decodeServerFrame, type Snapshot } from '../lib/battle/protocol';

vi.mock('../components/battle/BattleBoardCanvas', () => ({
    BattleBoardCanvas: ({ selectedIds }: { selectedIds: ReadonlySet<string> }) => <div data-testid="board-stub" data-selected={[...selectedIds].sort().join(',')} />,
}));

const R = (sourceId: number) => ({ kind: 'RETINUE' as const, sourceId });

function snapshot(over: Record<string, unknown> = {}): Snapshot {
    const r = decodeServerFrame(JSON.stringify({
        schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 120, eventSeq: '40', pacingMode: 'REALTIME', joinDeadlineAt: null,
        authorityRevision: '6', field: { boardId: 4, kind: 'FIELD' },
        units: [
            { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 14 }, troops: 780, morale: 96, order: 'CHARGE', rally: 'HOME' },
            { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 14 }, troops: 590, morale: 88, order: 'CHARGE', rally: 'HOME' },
            { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 15 }, troops: 500, morale: 100, order: 'DEFEND', rally: 'CENTER' },
        ],
        deployment: null,
        environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
        ...over,
    }));
    if (!r.ok || r.frame.t !== 'SNAPSHOT') throw new Error('decode');
    return r.frame;
}

function renderLive(over: Partial<Parameters<typeof BattleLive>[0]> = {}, snap = snapshot()) {
    const props = { view: toLiveView(snap, new Map()), terrainInputSha256: null, pendingCommand: null, notice: null, onCommand: vi.fn(), ...over };
    const utils = render(<BattleLive {...props} />);
    return { ...props, ...utils };
}

const rows = () => within(screen.getByRole('group', { name: '내 군단 부곡 고르기' }));

describe('실시간 전투 화면', () => {
    it('장수 머리 — 일부만 고르면 mixed, 누르면 그 장수 부곡 전부 · 판도 같은 고른 집합', () => {
        renderLive();
        const unit = rows().getAllByRole('checkbox', { name: /^부곡 1/ })[0];
        fireEvent.click(unit);
        const head = rows().getByRole('checkbox', { name: /^장수 1/ });
        expect(head).toHaveAttribute('aria-checked', 'mixed');
        fireEvent.click(head);
        expect(head).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByTestId('board-stub')).toHaveAttribute('data-selected', 'RETINUE:11,RETINUE:12');
        expect(screen.getByText('고른 부곡 2 / 3')).toBeInTheDocument();
    });

    it('「내 부곡 전부」 → 집결점이 섞여 보내지 않고 사유, 장수 1 묶음(모두 HOME) → 돌격은 sourceKeys · HOME', () => {
        const { onCommand } = renderLive();
        fireEvent.click(screen.getByRole('button', { name: '내 부곡 전부' }));
        fireEvent.click(screen.getByRole('button', { name: '돌격' }));
        expect(onCommand).not.toHaveBeenCalled();
        expect(screen.getByRole('status')).toHaveTextContent('집결점이 서로 달라');
        fireEvent.click(screen.getByRole('button', { name: '다 풀기' }));
        fireEvent.click(screen.getByRole('button', { name: '돌격' }));
        expect(screen.getByRole('status')).toHaveTextContent('부곡을 먼저 고르세요');
        fireEvent.click(rows().getByRole('checkbox', { name: /^장수 1/ }));
        fireEvent.click(screen.getByRole('button', { name: '돌격' }));
        expect(onCommand).toHaveBeenCalledWith({ sourceKeys: [R(11), R(12)] }, 2, 'CHARGE', 'HOME');
    });

    it('집결점이 모두 같으면 「내 부곡 전부」는 allMine 으로', () => {
        const same = snapshot({ units: [
            { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 14 }, troops: 780, morale: 96, order: 'CHARGE', rally: 'ENEMY' },
            { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 15 }, troops: 500, morale: 100, order: 'DEFEND', rally: 'ENEMY' },
        ] });
        const { onCommand } = renderLive({}, same);
        fireEvent.click(screen.getByRole('button', { name: '내 부곡 전부' }));
        fireEvent.click(screen.getByRole('button', { name: '수비' }));
        expect(onCommand).toHaveBeenCalledWith({ allMine: true }, 2, 'DEFEND', 'ENEMY');
    });

    it('알림 줄 — 보내는 중 · 받음 · 거절(쉬운 말)', () => {
        const a = renderLive({ pendingCommand: { clientCommandId: 'c1', order: 'ATTACK', count: 2 } });
        expect(screen.getByRole('status')).toHaveTextContent('보내는 중 — 공격 · 고른 부곡 2개');
        a.unmount();
        const b = renderLive({ notice: { kind: 'accepted', code: null, text: '명령 받음 — 고른 부곡 2개' } });
        expect(screen.getByRole('status')).toHaveTextContent('명령 받음');
        b.unmount();
        renderLive({ notice: { kind: 'rejected', code: 'INVALID_SCOPE', text: null } });
        expect(screen.getByRole('status')).toHaveTextContent('명령 거절 — 고른 부곡으로는 이 명령을 보낼 수 없습니다');
    });

    it('서버 대기 표지 — 남은 시간(rulePin) · 이름(units) · 사건(DELTA) · 상대(visibleEnemy), 규칙 핀이 오면 남은 시간', () => {
        const a = renderLive();
        expectServerWait(a.container, ['K6-14 · rulePin', 'K6-14 · units', 'K6-14 · DELTA', 'K6-14 · visibleEnemy']);
        a.unmount();
        const b = renderLive({}, snapshot({ tickHz: 10, maxTicks: 3000 }));
        expect(screen.getByRole('timer', { name: '남은 시간' })).toHaveTextContent('4:48');
        expectServerWaitGone(b.container, ['K6-14 · rulePin'], { value: '4:48' });
    });
});
