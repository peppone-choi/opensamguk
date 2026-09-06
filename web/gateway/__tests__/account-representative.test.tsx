import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

  it('lists owned generals and saves the chosen one from the server response', async () => {
    render(<RepresentativeSection />);
    const select = await screen.findByLabelText('대표 장수') as HTMLSelectElement;
    await waitFor(() => expect(select.options).toHaveLength(2));
    fireEvent.change(select, { target: { value: '1495' } });
    fireEvent.click(screen.getByRole('button', { name: '대표 장수 저장' }));
    expect(await screen.findByText('대표 장수를 추적w17(으)로 저장했습니다.')).toBeInTheDocument();
    expect(fetch).toHaveBeenLastCalledWith('/api/account/representative', expect.objectContaining({ method: 'POST', body: JSON.stringify({ generalId: 1495 }) }));
  });
});
