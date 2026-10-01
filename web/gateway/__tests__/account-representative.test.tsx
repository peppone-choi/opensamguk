import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RepresentativeSection from '@/components/account/RepresentativeSection';

describe('RepresentativeSection', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as { generalId: number | null };
        return { ok: body.generalId !== 999, status: body.generalId === 999 ? 400 : 200, text: async () => JSON.stringify(body.generalId === 999
          ? { error: '내 장수만 대표 장수로 정할 수 있습니다.' }
          : { current: { generalId: body.generalId, name: body.generalId == null ? null : '추적w17', worldId: body.generalId == null ? null : 1 }, candidates: [{ generalId: 1495, name: '추적w17', worldId: 1, scenarioCode: 'scenario_1010' }] }) } as Response;
      }
      return { ok: true, status: 200, text: async () => JSON.stringify({ current: { generalId: null, name: null, worldId: null }, candidates: [{ generalId: 1495, name: '추적w17', worldId: 1, scenarioCode: 'scenario_1010' }] }) } as Response;
    }));
  });

  it('lists owned generals as rows without the internal world number and saves the chosen one', async () => {
    render(<RepresentativeSection />);
    const list = await screen.findByRole('listbox', { name: '대표 장수' });
    const rows = within(list).getAllByRole('option');
    expect(rows.map((row) => row.textContent)).toEqual(['추적w17서버 정보 준비 중', '없음배지를 달지 않는다']);
    // K5-15 전: 월드 번호 · 시나리오 코드 원문을 보이지 않는다.
    expect(screen.queryByText(/월드|scenario_/)).toBeNull();
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(rows[0]);
    expect(rows[0]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('button', { name: '대표 장수 저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('대표 장수를 추적w17(으)로 저장했습니다.');
    expect(fetch).toHaveBeenLastCalledWith('/api/account/representative', expect.objectContaining({ method: 'POST', body: JSON.stringify({ generalId: 1495 }) }));
    expect(screen.getByText(/지금 대표 장수:/)).toHaveTextContent('지금 대표 장수: 추적w17 · 서버 정보 준비 중');
  });

  it('shows the empty state when the account has no general yet', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, text: async () => JSON.stringify({ current: { generalId: null, name: null, worldId: null }, candidates: [] }) }) as Response));
    render(<RepresentativeSection />);
    expect(await screen.findByText('아직 만든 장수가 없습니다 — 로비에서 서버를 고르세요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '대표 장수 저장' })).toBeNull();
  });

  it('shows a load failure with a retry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 502, text: async () => JSON.stringify({ error: '게이트웨이에 연결할 수 없습니다.' }) }) as Response));
    render(<RepresentativeSection />);
    expect(await screen.findByText('대표 장수를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('게이트웨이에 연결할 수 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /다시/ })).toBeInTheDocument();
  });

  it('keeps the choice and shows the server sentence when saving is refused', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => (init?.method === 'POST'
      ? { ok: false, status: 400, text: async () => JSON.stringify({ error: '내 장수만 대표 장수로 정할 수 있습니다.' }) }
      : { ok: true, status: 200, text: async () => JSON.stringify({ current: { generalId: null, name: null, worldId: null }, candidates: [{ generalId: 999, name: '남의장수', worldId: 1, scenarioCode: null }] }) }) as Response));
    render(<RepresentativeSection />);
    fireEvent.click(await screen.findByRole('option', { name: /남의장수/ }));
    fireEvent.click(screen.getByRole('button', { name: '대표 장수 저장' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('내 장수만 대표 장수로 정할 수 있습니다.');
    expect(screen.getByRole('option', { name: /남의장수/ })).toHaveAttribute('aria-selected', 'true');
  });
});
