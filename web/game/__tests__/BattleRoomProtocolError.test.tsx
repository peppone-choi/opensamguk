import type { ReactNode } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import BattleRoomPage from '@/app/game/(campaign)/corps/battle/[id]/page';
import { useBattleSession } from '@/lib/battle/use-battle-session';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { decodeJoiningSnapshot } from '@/lib/battle/joining-snapshot';

const { push, retry } = vi.hoisted(() => ({ push: vi.fn(), retry: vi.fn() }));
vi.mock('next/navigation', () => ({
    useParams: () => ({ id: 'battle-v2' }),
    useSearchParams: () => new URLSearchParams('world=1'),
    useRouter: () => ({ push }),
}));
vi.mock('@/components/GameShell', () => ({ default: ({ children }: { children: ReactNode }) => <main>{children}</main> }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep' }) }));
vi.mock('@/lib/serverGameUrl', () => ({ useServerGameUrl: () => '/game/corps/battle?server=pep' }));
vi.mock('@/lib/battle/use-battle-session', () => ({ useBattleSession: vi.fn() }));
vi.mock('@/components/battle/BattleBoardCanvas', () => ({ BattleBoardCanvas: () => <div data-testid="joining-board" /> }));

beforeEach(() => vi.clearAllMocks());

test('전투 자료 오류는 준비 중이나 연결 단절로 바꾸지 않고 다시 읽기와 목록 복귀를 제공한다', () => {
    vi.mocked(useBattleSession).mockReturnValue({ session: { state: 'protocol-error' }, move: vi.fn(), command: vi.fn(), retry } as ReturnType<typeof useBattleSession>);
    render(<BattleRoomPage />);
    expect(screen.getByText('전투 자료를 읽을 수 없습니다')).toBeInTheDocument();
    expect(screen.getByText('서버가 보낸 전투 자료를 확인할 수 없습니다. 다시 읽어 주세요.')).toBeInTheDocument();
    expect(screen.queryByText(/서버 준비 중|전투 연결이 끊겼습니다|기본 배치대로/)).not.toBeInTheDocument();
    expect(screen.queryByText(/SNAPSHOT_UNITS|sourceKey|synthetic/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(push).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '전투 · 부재 대비로' }));
    expect(push).toHaveBeenCalledWith('/game/corps/battle?server=pep');
});

test.each(['NO_WORLD', 'TICKET', 'SOCKET'] as const)('기존 unavailable %s 의미는 자료 오류로 바꾸지 않는다', reason => {
    vi.mocked(useBattleSession).mockReturnValue({ session: { state: 'unavailable', reason }, move: vi.fn(), command: vi.fn(), retry } as ReturnType<typeof useBattleSession>);
    render(<BattleRoomPage />);
    expect(screen.getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeInTheDocument();
    expect(screen.queryByText('전투 자료를 읽을 수 없습니다')).not.toBeInTheDocument();
    expect(retry).not.toHaveBeenCalled();
});

test('actual JOINING state selects the separate read-only room without legacy deployment/live views', () => {
    const snapshot = decodeJoiningSnapshot(JSON.parse(readFileSync(resolve(__dirname, '../../../app/game-api/src/test/resources/battle/v2-joining-snapshot.json'), 'utf8')))!;
    const move = vi.fn(); const command = vi.fn();
    vi.mocked(useBattleSession).mockReturnValue({ session: { state: 'joining', snapshot }, move, command, retry });
    render(<BattleRoomPage />);
    expect(screen.getByTestId('battle-joining-snapshot')).toBeInTheDocument();
    expect(screen.getByText('1. 부곡 701')).toBeInTheDocument();
    expect(screen.queryByText('전투 자료를 읽을 수 없습니다')).not.toBeInTheDocument();
    expect(screen.queryByTestId('battle-join')).not.toBeInTheDocument();
    expect(screen.queryByTestId('battle-live')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /1. 부곡 701/ }));
    expect(move).not.toHaveBeenCalled(); expect(command).not.toHaveBeenCalled();
});
