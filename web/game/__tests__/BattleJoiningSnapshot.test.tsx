import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { BattleJoiningSnapshot } from '../components/battle/BattleJoiningSnapshot';
import { decodeJoiningSnapshot } from '../lib/battle/joining-snapshot';

vi.mock('../components/battle/BattleBoardCanvas', () => ({
  BattleBoardCanvas: ({ units, allowedCells, onPickUnit, onPickCell }: any) => (
    <div data-testid="projection" data-units={JSON.stringify(units)} data-allowed={JSON.stringify(allowedCells)}>
      <button onClick={() => onPickUnit(units[0]?.id)}>판에서 부곡 보기</button>
      <button onClick={() => onPickCell({ row: 0, col: 1 })}>판 빈 칸 누르기</button>
    </div>
  ),
}));
const golden = JSON.parse(readFileSync(resolve(__dirname, '../../../app/game-api/src/test/resources/battle/v2-joining-snapshot.json'), 'utf8'));
const snapshot = decodeJoiningSnapshot(golden)!;

test('actual source facts render read-only with stable string identity and no owner/morale/command inventions', () => {
  render(<BattleJoiningSnapshot snapshot={snapshot} />);
  expect(screen.getByRole('heading', { name: '전투 참가 대기' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '내 부곡 1개' })).toBeInTheDocument();
  expect(screen.getByText('1. 부곡 701')).toBeInTheDocument();
  expect(screen.getByText('병력 100 · 행 0, 열 0')).toBeInTheDocument();
  expect(screen.queryByText(/사기|장수|대리 지휘|집결|기본 배치 확정/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /옮기기|돌격|공격|후퇴/ })).not.toBeInTheDocument();
  const board = screen.getByTestId('projection');
  expect(JSON.parse(board.dataset.units!)).toEqual([{ id: 'RETINUE:701', cell: { row: 0, col: 0 }, index: 1 }]);
  expect(JSON.parse(board.dataset.allowed!)).toEqual(golden.deployment.allowedCells);
  fireEvent.click(screen.getByRole('button', { name: '1. 부곡 701 병력 100 · 행 0, 열 0' }));
  expect(screen.getByRole('status')).toHaveTextContent('고른 부곡 701: 행 0, 열 0 · 병력 100');
  fireEvent.click(screen.getByRole('button', { name: '판 빈 칸 누르기' }));
  expect(screen.getByRole('status')).toHaveTextContent('행 0, 열 0');
});

test('empty owner projection remains an honest empty read view', () => {
  render(<BattleJoiningSnapshot snapshot={{ ...snapshot, ownUnits: [], deployment: { ...snapshot.deployment, ownPositions: [] } }} />);
  expect(screen.getByText('이 전투에서 내 지휘권에 속하는 부곡이 없습니다.')).toBeInTheDocument();
  expect(screen.queryByText('1. 부곡 701')).not.toBeInTheDocument();
});

test('refreshed projection clears stale inspection without inserting absent sources', () => {
  const page = render(<BattleJoiningSnapshot snapshot={snapshot} />);
  fireEvent.click(screen.getByRole('button', { name: /1. 부곡 701/ }));
  page.rerender(<BattleJoiningSnapshot snapshot={{ ...snapshot, ownUnits: [], deployment: { ...snapshot.deployment, ownPositions: [] } }} />);
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  expect(JSON.parse(screen.getByTestId('projection').dataset.units!)).toEqual([]);
});
