import { fireEvent, render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SelectCityField from '@/components/command/SelectCityField';

const mocks = vi.hoisted(() => ({
  mapPreview: vi.fn(),
  gameConst: vi.fn(),
  frontInfo: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    mapPreview: mocks.mapPreview,
    gameConst: mocks.gameConst,
    frontInfo: mocks.frontInfo,
  },
}));

vi.mock('@/components/game/MapViewer', () => ({
  default: () => <div data-testid="map-viewer" />,
}));

describe('SelectCityField', () => {
  it('shows one-hop Licheng from server city constants and submits stable id 781', async () => {
    const onChange = vi.fn();
    mocks.mapPreview.mockResolvedValue({
      serverName: 'test', year: 200, month: 1, mapCode: 'han-world-v3', width: 700, height: 610,
      cities: [
        { id: 273, name: '노', level: 5, nationId: 1, x: 411, y: 180, state: 0, supply: true, isCapital: false },
        { id: 781, name: '역성', level: 11, nationId: 1, x: 413, y: 175, state: 0, supply: true, isCapital: false },
      ],
      nations: [],
    });
    mocks.gameConst.mockResolvedValue({
      result: true,
      cityConst: [
        { id: 273, name: '노', path: { 781: '역성' } },
        { id: 781, name: '역성', path: { 273: '노' } },
      ],
    });
    mocks.frontInfo.mockResolvedValue({ general: { cityId: 273 } });

    const { container } = render(
      <SelectCityField commandKey="che_이동" value={null} onChange={onChange} />,
    );

    await waitFor(() => {
      expect(container.querySelector('.cmd-city-distance-row.d1 button')).not.toBeNull();
    });
    const button = container.querySelector('.cmd-city-distance-row.d1 button') as HTMLButtonElement;
    expect(button?.textContent).toContain('역성');
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledWith(781);
  });
});
