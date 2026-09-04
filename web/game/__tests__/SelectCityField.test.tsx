import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SelectCityField from '@/components/command/SelectCityField';

const mocks = vi.hoisted(() => ({
  mapPreview: vi.fn(),
  gameConst: vi.fn(),
  frontInfo: vi.fn(),
  mapSelect: null as ((cityId: number) => void) | null,
}));

vi.mock('@/lib/api', () => ({
  api: {
    mapPreview: mocks.mapPreview,
    gameConst: mocks.gameConst,
    frontInfo: mocks.frontInfo,
  },
}));

vi.mock('@/components/game/MapViewer', () => ({
  default: (props: { onCitySelect?: (cityId: number) => void }) => {
    mocks.mapSelect = props.onCitySelect ?? null;
    return <div />;
  },
}));

const map = {
  serverName: '테스트', year: 200, month: 1, mapCode: 'han', width: 700, height: 610,
  cities: [
    {
      id: 1, name: '성도현', level: 8, nationId: 1, x: 10, y: 10, region: 1,
      regionName: '익주', commanderyName: '촉군', isCommanderySeat: true, provinceId: 101,
      state: 0, supply: true, isCapital: true,
    },
    {
      id: 202, name: '면죽현', level: 4, nationId: 1, x: 20, y: 20, region: 1,
      regionName: '익주', commanderyName: '광한군', isCommanderySeat: false, provinceId: 202,
      state: 0, supply: true, isCapital: false,
    },
    {
      id: 303, name: '평양현', level: 4, nationId: 2, x: 30, y: 30, region: 2,
      regionName: '청주', commanderyName: '제군', isCommanderySeat: true, provinceId: 303,
      state: 0, supply: true, isCapital: false,
    },
    {
      id: 404, name: '대현', level: 4, nationId: 2, x: 40, y: 40, region: 3,
      regionName: '유주', commanderyName: '대군', isCommanderySeat: true, provinceId: 404,
      state: 0, supply: true, isCapital: false,
    },
    {
      id: 505, name: '양평현', level: 4, nationId: 2, x: 50, y: 50, region: 3,
      regionName: '유주', commanderyName: '요동군', isCommanderySeat: true, provinceId: 505,
      state: 0, supply: true, isCapital: false,
    },
    {
      id: 606, name: '번우현', level: 4, nationId: 0, x: 60, y: 60, region: 4,
      regionName: '교주', commanderyName: '남해군', isCommanderySeat: true, provinceId: 606,
      state: 0, supply: false, isCapital: false,
    },
  ],
  nations: [
    { id: 1, name: '촉', color: '#ff0000' },
    { id: 2, name: '위', color: '#0000ff' },
  ],
};

const cityConst = [
  { id: 1, name: '성도현', level: 8, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 1, posX: 0, posY: 0, path: { 202: '' } },
  { id: 202, name: '면죽현', level: 4, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 1, posX: 0, posY: 0, path: { 1: '', 303: '' } },
  { id: 303, name: '평양현', level: 4, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 2, posX: 0, posY: 0, path: { 202: '', 404: '' } },
  { id: 404, name: '대현', level: 4, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 3, posX: 0, posY: 0, path: { 303: '', 505: '' } },
  { id: 505, name: '양평현', level: 4, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 3, posX: 0, posY: 0, path: { 404: '' } },
  { id: 606, name: '번우현', level: 4, population: 0, agriculture: 0, commerce: 0, security: 0, defence: 0, wall: 0, region: 4, posX: 0, posY: 0, path: {} },
];

beforeEach(() => {
  mocks.mapSelect = null;
  mocks.mapPreview.mockReset().mockResolvedValue(map);
  mocks.gameConst.mockReset().mockResolvedValue({ cityConst, gameConst: {} });
  mocks.frontInfo.mockReset().mockResolvedValue({
    general: { cityId: 1, nationId: 1 },
    nation: { capitalCityId: 1 },
  });
});

describe('SelectCityField county-scale selection', () => {
  it('finds a county by commandery or numeric id and submits its stable city id', async () => {
    const onChange = vi.fn();
    render(<SelectCityField commandName="천도" value={null} onChange={onChange} />);

    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: '광한군' } });

    const commanderyMatch = screen.getByRole('option', { name: /익주 › 광한군 › 면죽현/ });
    expect(commanderyMatch).toHaveTextContent('아국령');

    fireEvent.change(input, { target: { value: '202' } });
    fireEvent.click(screen.getByRole('option', { name: /면죽현/ }));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(202));
  });

  it('limits movement candidates to counties one edge from the current county', async () => {
    const onChange = vi.fn();
    render(<SelectCityField commandName="이동" value={null} onChange={onChange} />);

    expect(await screen.findByRole('option', { name: /면죽현/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /성도현/ })).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '평양현' } });

    expect(screen.queryByRole('option', { name: /평양현/ })).not.toBeInTheDocument();
    expect(screen.getByText('일치하는 항목 없음')).toBeInTheDocument();

    act(() => mocks.mapSelect?.(303));
    expect(onChange).not.toHaveBeenCalled();
    act(() => mocks.mapSelect?.(202));
    expect(onChange).toHaveBeenCalledWith(202);
  });

  it.each(['출병', '첩보', '화계'])('%s keeps distant valid targets searchable', async (commandName) => {
    render(<SelectCityField commandName={commandName} value={null} onChange={vi.fn()} />);

    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: '요동군' } });

    expect(screen.getByRole('option', { name: /유주 › 요동군 › 양평현/ })).toBeInTheDocument();
  });

  it('applies reconnaissance and fire-attack ownership rules to both search and map selection', async () => {
    const scoutChange = vi.fn();
    const scout = render(<SelectCityField commandName="첩보" value={null} onChange={scoutChange} />);
    const scoutInput = await screen.findByRole('textbox');

    fireEvent.change(scoutInput, { target: { value: '광한군' } });
    expect(screen.queryByRole('option', { name: /면죽현/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '면죽현' })).not.toBeInTheDocument();
    act(() => mocks.mapSelect?.(202));
    expect(scoutChange).not.toHaveBeenCalled();
    scout.unmount();

    const fireChange = vi.fn();
    render(<SelectCityField commandName="화계" value={null} onChange={fireChange} />);
    const fireInput = await screen.findByRole('textbox');

    fireEvent.change(fireInput, { target: { value: '남해군' } });
    expect(screen.queryByRole('option', { name: /번우현/ })).not.toBeInTheDocument();
    act(() => mocks.mapSelect?.(606));
    expect(fireChange).not.toHaveBeenCalled();

    fireEvent.change(fireInput, { target: { value: '요동군' } });
    expect(screen.getByRole('option', { name: /양평현/ })).toBeInTheDocument();
    act(() => mocks.mapSelect?.(505));
    expect(fireChange).toHaveBeenCalledWith(505);
  });
});
