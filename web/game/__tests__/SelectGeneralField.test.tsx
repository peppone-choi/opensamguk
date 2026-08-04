import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SelectGeneralField from '@/components/command/SelectGeneralField';

const mocks = vi.hoisted(() => ({
  myGenerals: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    myGenerals: mocks.myGenerals,
  },
}));

describe('SelectGeneralField', () => {
  it('shows all five stats for each eligible general', async () => {
    const onChange = vi.fn();
    mocks.myGenerals.mockResolvedValue({
      result: true,
      nationId: 1,
      generals: [
        {
          generalId: 10,
          name: '순욱',
          cityId: 5,
          officerLevel: 5,
          leadership: 70,
          strength: 40,
          intel: 96,
          politics: 98,
          charm: 88,
          crew: 300,
          npcState: 0,
          mine: false,
          refreshScoreTotal: 0,
        },
        {
          generalId: 99,
          name: '나 자신',
          cityId: 5,
          officerLevel: 1,
          leadership: 1,
          strength: 1,
          intel: 1,
          politics: 1,
          charm: 1,
          crew: 0,
          npcState: 0,
          mine: true,
          refreshScoreTotal: 0,
        },
      ],
    });

    render(<SelectGeneralField value={null} onChange={onChange} ownGeneralId={99} />);

    const option = await screen.findByRole('option', { name: /순욱/ });
    expect(option).toHaveTextContent('순욱 (통 70 / 무 40 / 지 96 / 정치 98 / 매력 88)');
    expect(screen.queryByRole('option', { name: /나 자신/ })).not.toBeInTheDocument();

    fireEvent.click(option);
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(10));
  });
});
