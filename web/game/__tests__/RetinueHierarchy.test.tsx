import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { RetinueHierarchy } from '@/components/retinue/RetinueHierarchy';
import { HierarchyReadError, readRetinueHierarchy } from '@/lib/api/retinue-hierarchy';
import { hierarchyFixture } from './fixtures/retinue-hierarchy';

let actor: number | null = 7;
let serverId = 'pep';
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: actor, serverId, frontInfo: { global: { year: 200, month: 3, turnPhase: 1 } } }) }));
vi.mock('@/lib/api/retinue-hierarchy', async (load) => ({ ...await load<object>(), readRetinueHierarchy: vi.fn() }));
const read = vi.mocked(readRetinueHierarchy);
beforeEach(() => { actor = 7; serverId = 'pep'; read.mockReset().mockResolvedValue(hierarchyFixture()); });

it('renders direct and total counts, three levels, ancestor order and no command actions', async () => {
  render(<RetinueHierarchy />);
  const list = await screen.findByRole('list', { name: '내 부 계층' });
  const self = within(list).getAllByRole('listitem')[0];
  expect(self).toHaveTextContent('직속 장수2명');
  expect(self).toHaveTextContent('전체 휘하3명');
  expect(within(list).getByText('가상 하위').closest('li')).toHaveAttribute('data-depth', '2');
  expect(screen.getByRole('list', { name: '상관 계보' })).toHaveTextContent('직속 상관가상 상관2단계 상관가상 최상위');
  expect(screen.queryByRole('button')).toBeNull();
  expect(screen.queryByRole('link')).toBeNull();
});
it('keeps self and superiors when direct people are empty', async () => {
  read.mockResolvedValue(hierarchyFixture(true));
  render(<RetinueHierarchy />);
  expect(await screen.findByText('아직 직속 장수가 없습니다.')).toBeInTheDocument();
  expect(screen.getByText('본인')).toBeInTheDocument();
  expect(screen.getByText('가상 상관')).toBeInTheDocument();
});
it('shows loading then unavailable, retry reads again without inventing an empty hierarchy', async () => {
  read.mockResolvedValueOnce({ status: 'UNAVAILABLE', actorGeneralId: 7, superiors: [], nodes: [] });
  render(<RetinueHierarchy />);
  expect(screen.queryByText('아직 직속 장수가 없습니다.')).toBeNull();
  fireEvent.click(await screen.findByRole('button', { name: '다시 읽기' }));
  expect(await screen.findByRole('list', { name: '내 부 계층' })).toBeInTheDocument();
  expect(read).toHaveBeenCalledTimes(2);
});
it.each([401, 403])('shows denied HTTP %s without a retry or stale tree', async (status) => {
  read.mockRejectedValue(new HierarchyReadError(status));
  render(<RetinueHierarchy />);
  expect(await screen.findByText(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '본인의 부 조직도만 볼 수 있습니다.')).toBeInTheDocument();
  expect(screen.queryByRole('list')).toBeNull();
  expect(screen.queryByRole('button')).toBeNull();
});
it('network failure is not empty and retry can restore the hierarchy', async () => {
  read.mockRejectedValueOnce(new Error('network'));
  render(<RetinueHierarchy />);
  fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
  expect(await screen.findByText('가상 하위')).toBeInTheDocument();
});
it('discards previous actor immediately and ignores a late response after session replacement', async () => {
  let resolveOld!: (data: ReturnType<typeof hierarchyFixture>) => void;
  read.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
  const view = render(<RetinueHierarchy />);
  actor = 8; serverId = 'other';
  read.mockResolvedValue({ ...hierarchyFixture(true), actorGeneralId: 8, superiors: [], nodes: [{ generalId: 8, name: '새 본인', parentId: null, directCount: 0, descendantCount: 0 }] });
  view.rerender(<RetinueHierarchy />);
  expect(await screen.findByText('새 본인')).toBeInTheDocument();
  resolveOld(hierarchyFixture());
  await waitFor(() => expect(screen.queryByText('가상 상관')).toBeNull());
  expect(read.mock.calls[0][1]?.aborted).toBe(true);
});
