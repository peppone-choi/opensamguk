// 전투 참가 · 배치 화면(P-C03) — 판은 흉내(캔버스 없음). 남은 시간 · 장수별 목록 · 고르기 → 칸 누름이 고른 부곡으로 · 거절 · 막힘 사유 ·
// 전장 길이는 서버 규칙 핀이 있을 때만(보드 「5분 · 3,000틱」은 예시) · 서버가 못 준 값은 「서버 대기」.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { BattleJoin } from '../components/battle/BattleJoin';
import { toJoinView } from '../lib/battle/join-view';
import { decodeServerFrame, type Snapshot } from '../lib/battle/protocol';

vi.mock('../components/battle/BattleBoardCanvas', () => ({
    BattleBoardCanvas: ({ onPickCell, selectedId }: { onPickCell: (c: { row: number; col: number }) => void; selectedId: string | null }) => (
        <div data-testid="board-stub" data-selected={selectedId ?? ''}>
            <button type="button" onClick={() => onPickCell({ row: 33, col: 12 })}>칸 33,12 누르기</button>
        </div>
    ),
}));

const R = (sourceId: number) => ({ kind: 'RETINUE' as const, sourceId });

function snapshot(over: Record<string, unknown> = {}): Snapshot {
    const r = decodeServerFrame(JSON.stringify({
        schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 0, eventSeq: '1', pacingMode: 'REALTIME', joinDeadlineAt: null,
        authorityRevision: '5', field: { boardId: 4, kind: 'FIELD' },
        units: [
            { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 10 }, troops: 800, morale: 100 },
            { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 10 }, troops: 600, morale: 90 },
            { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 12 }, troops: 500, morale: 100 },
        ],
        deployment: { revision: '0', defaultPinned: true, remainingMillis: 42_000, allowedCells: [{ row: 33, col: 12 }], ownPositions: [] },
        environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
        ...over,
    }));
    if (!r.ok || r.frame.t !== 'SNAPSHOT') throw new Error('decode');
    return r.frame;
}

function renderJoin(over: Partial<Parameters<typeof BattleJoin>[0]> = {}, snap = snapshot()) {
    const props = { view: toJoinView(snap, Date.now()), terrainInputSha256: null, pending: null, notice: null, onMove: vi.fn(), ...over };
    render(<BattleJoin {...props} />);
    return props;
}

describe('전투 참가 · 배치 화면', () => {
    it('남은 시간 · 「부곡 n개가 모두 나간다」 · 장수별 목록(이름은 서버 대기 — 차례 번호만)', () => {
        renderJoin();
        expect(screen.getByRole('timer', { name: '개전까지 남은 시간' })).toHaveTextContent(/^0:4[12]$/);
        expect(screen.getByText('내 군단 부곡 3개가 모두 나간다')).toBeInTheDocument();
        const list = screen.getByRole('listbox', { name: '내 군단 부곡' });
        expect(within(within(list).getByRole('group', { name: '장수 1' })).getAllByRole('option')).toHaveLength(2);
        expect(within(list).getByRole('group', { name: '장수 2' })).toHaveTextContent('병력 합 500');
        expect(screen.getByText('장수 이름 · 초상 · 부곡 이름 · 병종은 서버가 아직 주지 않습니다.')).toBeInTheDocument();
    });

    it('부곡을 고르고 칸을 누르면 그 부곡 · 그 칸으로 onMove', () => {
        const { onMove } = renderJoin();
        const options = within(screen.getByRole('listbox', { name: '내 군단 부곡' })).getAllByRole('option');
        expect(options[0]).toHaveAttribute('aria-selected', 'true');
        fireEvent.click(options[1]);
        expect(options[1]).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByTestId('board-stub')).toHaveAttribute('data-selected', 'RETINUE:12');
        fireEvent.click(screen.getByRole('button', { name: '칸 33,12 누르기' }));
        expect(onMove).toHaveBeenCalledWith('RETINUE:12', { row: 33, col: 12 });
    });

    it('알림 줄 — 보내는 중 · 서버 거절(쉬운 말) · 표 밖 거절 · 화면이 막은 사유', () => {
        const { unmount } = render(<BattleJoin view={toJoinView(snapshot(), Date.now())} terrainInputSha256={null} onMove={vi.fn()} notice={null}
            pending={{ clientCommandId: 'c1', unitId: 'RETINUE:11', cell: { row: 33, col: 12 } }} />);
        expect(screen.getByRole('status')).toHaveTextContent('옮기는 중');
        unmount();
        renderJoin({ notice: { kind: 'rejected', code: 'STALE_DEPLOYMENT', text: null } });
        expect(screen.getByRole('status')).toHaveTextContent('배치가 먼저 바뀌었습니다');
    });

    it('서버 대기 칸은 기다리는 계약판 행을 단다 — 이름 · 병종(K6-14 · units) · 상대(visibleEnemy) · 날씨 · 목표(A11) · 길이(rulePin)', () => {
        const { container } = render(<BattleJoin view={toJoinView(snapshot(), Date.now())} terrainInputSha256={null} pending={null} notice={null} onMove={vi.fn()} />);
        const field = screen.getByRole('region', { name: '전장' });
        expect(field).toHaveTextContent('4번 판');
        expect(within(field).getAllByText('서버 대기')).toHaveLength(3);
        expect(screen.queryByText(/3,000틱/)).toBeNull();
        expectServerWait(container, ['K6-14 · units', 'K6-14 · visibleEnemy', 'A11', 'K6-14 · rulePin']);
    });

    it('규칙 핀(tickHz · maxTicks)이 오면 길이 「5분 · 3,000틱」 — rulePin 표지는 사라진다', () => {
        const { container } = render(<BattleJoin view={toJoinView(snapshot({ tickHz: 10, maxTicks: 3000 }), Date.now())} terrainInputSha256={null} pending={null} notice={null} onMove={vi.fn()} />);
        expect(within(screen.getByRole('region', { name: '전장' })).getByText('5분 · 3,000틱')).toBeInTheDocument();
        expectServerWaitGone(container, ['K6-14 · rulePin'], { value: '5분 · 3,000틱' });
        expectServerWait(container, ['K6-14 · units', 'K6-14 · visibleEnemy', 'A11']);
    });
});
