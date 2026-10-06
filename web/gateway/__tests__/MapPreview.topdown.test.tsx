import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { TopdownMap as TopdownMapType } from '@opensamguk/ui/map/topdown';

// 로그인 · 로비 지도 미리보기의 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS) 경로. 지도 그리기(GL)는 가짜로 두고,
// 어느 지도를 고르는지 · bake 주소 · 이름표 · 이름 단추 · 세력색 대조 · 받는 요청만 본다.
const shared = vi.hoisted(() => ({
  topdown: null as ComponentProps<typeof TopdownMapType> | null,
  places: { provinceCount: 2 } as { provinceCount: number },
}));

vi.mock('@opensamguk/ui', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
  return { ...actual,
    useWorldMap: ({ mapData }: { mapData?: MapData }) => (mapData
      ? { kind: 'ready' as const, preview: mapData, tiles: { _meta: { cols: 768, rows: 669 } }, tilesSha256: 'test',
        provinceMap: null, markerPositions: new Map(), cities: actual.buildWorldCities(mapData),
        sourceSize: { width: mapData.width, height: mapData.height } }
      : { kind: 'loading' as const }),
    WorldMapCanvas: () => <div data-testid="world-map" />,
  };
});

vi.mock('@opensamguk/ui/map/topdown', async () => {
  const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
  return { ...actual,
    loadBakePlaces: async () => shared.places,
    cityCell: () => ({ col: 10, row: 10 }),
    TopdownMap: (props: ComponentProps<typeof TopdownMapType>) => {
      shared.topdown = props;
      return <div data-testid="topdown-map" className={props.className}>
        <button type="button" onClick={() => props.onSelect?.({ kind: 'city', id: 11, cell: { col: 10, row: 10 } })}>城 누르기</button>
        <button type="button" onClick={() => props.onSelect?.({ kind: 'none', id: null, cell: { col: 0, row: 0 } })}>빈 땅 누르기</button>
        <button type="button" onClick={() => props.onStatus?.('unsupported')}>WebGL2 없음</button>
      </div>;
    },
  };
});

import MapPreview, { type MapData } from '@/components/MapPreview';
import { installViewport } from '@opensamguk/ui';

const BAKE = 'b'.repeat(64);
const MAP: MapData = {
  serverName: '테스트섭', year: 200, month: 5, turnPhaseText: '상순', mapCode: 'han-world-v3',
  width: 100, height: 100,
  cities: [{ id: 11, name: '낙양', level: 8, nationId: 1, x: 50, y: 50, isCapital: true }],
  nations: [{ id: 1, name: '위', color: '#ff0000' }],
  provinceOccupancy: [
    { provinceRecordId: 'A', provinceIndex: 0, nationId: 1 },
    { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 },
  ],
  topdownBakeId: BAKE,
};

// 새 지도는 따로 받는 묶음(React.lazy)이다. 첫 시험이 그 모듈 변환을 findBy 1초 안에 기다리다 부하에서 떨어진 적이 있어
// 미리 한 번 불러 둔다 — 시험마다 같은 모듈 캐시에서 바로 풀린다.
beforeAll(async () => {
  await import('@/components/TopdownMapPreview');
}, 60_000);

let fetched: string[] = [];
function servePreview(body: unknown) {
  fetched = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    fetched.push(url);
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
}

beforeEach(() => {
  shared.topdown = null;
  shared.places = { provinceCount: 2 };
  vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: () => null,
    get length() { return values.size; },
  });
});
afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('지도 미리보기 — 교체 스위치 빌드', () => {
  it('서버가 bakeId를 주면 새 지도: 게임 프록시의 bake를 천하 보기로, 옛 지형은 받지 않는다', async () => {
    servePreview(MAP);
    const onPreview = vi.fn();
    render(<MapPreview serverId="pep" variant="backdrop" onPreview={onPreview} />);
    await screen.findByTestId('topdown-map');
    expect(shared.topdown!.source).toEqual({ bakeUrl: `/api/game/api/map/topdown/${BAKE}?server=pep`, kitUrl: '/map/waryong/273d596' });
    // 배경은 화면을 채운다(설계서 §2 · 보드 V31K5Login — 짧은 쪽 맞춤이면 좌우 204px 가 비었다, K10 실지도 10-03).
    expect(shared.topdown!.initialView).toBe('cover');
    expect(shared.topdown!.className).toBe('map-preview-han');
    expect(screen.queryByTestId('world-map')).toBeNull();
    // 받는 것은 미리보기 한 번뿐(지형 · 省 그림은 옛 지도판 몫)
    expect(fetched).toEqual(['/api/server-map/pep']);
    expect(onPreview).toHaveBeenCalledTimes(1);
    // 구역 수 · 번호가 bake와 맞으면 세력색을 넘긴다
    await waitFor(() => expect(shared.topdown!.world).toBeDefined());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('이름표는 지도 위 고정 판을 피한다(상자는 지도 기준) — 로비 상자(panel)도 칸을 채운다', async () => {
    servePreview(MAP);
    document.body.insertAdjacentHTML('beforeend', '<div class="test-plate"></div>');
    const plate = document.querySelector('.test-plate')!;
    const box = (left: number, top: number, width: number, height: number) => ({ left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) }) as DOMRect;
    const spy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this === plate) return box(12, 392, 220, 88);
      if (this.classList.contains('map-preview-canvas')) return box(0, 0, 390, 480);
      return box(0, 0, 0, 0);
    });
    const { unmount } = render(<MapPreview serverId="pep" variant="backdrop" avoidSelector=".test-plate" />);
    await screen.findByTestId('topdown-map');
    await waitFor(() => expect(shared.topdown!.labelAvoid).toEqual([{ x: 12, y: 392, width: 220, height: 88 }]));
    unmount();
    spy.mockRestore();
    plate.remove();

    // 로비 펼친 지도도 같은 카드의 작은 지도(그림이 칸을 채움) 원칙을 따른다(K0 10-03 질문 5).
    render(<MapPreview serverId="pep" />);
    await screen.findByTestId('topdown-map');
    expect(shared.topdown!.initialView).toBe('cover');
    expect(shared.topdown!.labelAvoid).toEqual([]);
  });

  it('로그인 조작(zoom)은 + · − · 「이름」 — 모바일은 지도 안, +/−는 지도 손잡이로 한 칸씩', async () => {
    const viewport = installViewport(390);
    try {
      servePreview(MAP);
      render(<MapPreview serverId="pep" variant="backdrop" controls="zoom" controlsHostId="ctl-host" />);
      await screen.findByTestId('topdown-map');
      const zoomStep = vi.fn();
      act(() => { shared.topdown!.onReady?.({ zoomStep, setLevel: vi.fn(), centerOn: vi.fn(), focusCity: vi.fn() }); });
      const group = screen.getByRole('group', { name: '지도 조작' });
      expect(group.closest('.map-preview')).not.toBeNull();
      expect(within(group).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['확대', '축소', '지도 이름 보이기']);
      fireEvent.click(within(group).getByRole('button', { name: '확대' }));
      fireEvent.click(within(group).getByRole('button', { name: '축소' }));
      expect(zoomStep.mock.calls).toEqual([[1], [-1]]);
    } finally {
      viewport.restore();
    }
  });

  it('데스크톱 로그인은 조작 묶음을 로그인 카드 아래 자리로 내보낸다(D41)', async () => {
    const viewport = installViewport(1440);
    document.body.insertAdjacentHTML('beforeend', '<div id="ctl-host"></div>');
    try {
      servePreview(MAP);
      render(<MapPreview serverId="pep" variant="backdrop" controls="zoom" controlsHostId="ctl-host" />);
      await screen.findByTestId('topdown-map');
      const host = document.getElementById('ctl-host')!;
      await waitFor(() => expect(within(host).getByRole('group', { name: '지도 조작' })).toBeInTheDocument());
      expect(document.querySelectorAll('.map-preview [role="group"][aria-label="지도 조작"]')).toHaveLength(0);
    } finally {
      document.getElementById('ctl-host')?.remove();
      viewport.restore();
    }
  });

  it('가입(none)에는 지도 조작이 없다(보드 V31K5Join · MJoin)', async () => {
    servePreview(MAP);
    render(<MapPreview serverId="pep" variant="backdrop" controls="none" />);
    await screen.findByTestId('topdown-map');
    expect(screen.queryByRole('group', { name: '지도 조작' })).toBeNull();
    expect(screen.queryByRole('button', { name: '지도 이름 보이기' })).toBeNull();
  });

  it('城을 누르면 옛 지도판과 같은 이름표, 빈 땅을 누르면 거둔다', async () => {
    servePreview(MAP);
    render(<MapPreview serverId="pep" variant="backdrop" />);
    fireEvent.click(await screen.findByRole('button', { name: '城 누르기' }));
    const tip = screen.getByRole('status');
    expect(tip).toHaveTextContent('낙양');
    expect(tip).toHaveTextContent('위 · 수도');
    expect(tip).toHaveClass('map-preview-tooltip--pinned');
    fireEvent.click(screen.getByRole('button', { name: '빈 땅 누르기' }));
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('「이름」 단추는 새 지도의 城 이름 층을 끄고 켠다', async () => {
    servePreview(MAP);
    render(<MapPreview serverId="pep" variant="backdrop" />);
    await screen.findByTestId('topdown-map');
    expect(shared.topdown!.layers?.cityNames).toBe(true);
    const toggle = screen.getByRole('button', { name: '지도 이름 보이기' });
    act(() => { fireEvent.click(toggle); });
    expect(shared.topdown!.layers?.cityNames).toBe(false);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(window.localStorage.getItem('opensamguk.map.hideNames')).toBe('yes');
  });

  it('WebGL2가 없으면 안내를 지도 아래 끝이 아니라 이름표 자리에 띄운다(로그인 패널에 가리지 않게)', async () => {
    servePreview(MAP);
    render(<MapPreview serverId="pep" variant="backdrop" />);
    await screen.findByTestId('topdown-map');
    // 지도 자신의 안내(아래 끝)는 끄고 상태만 받는다
    expect(shared.topdown!.notices).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '城 누르기' }));
    fireEvent.click(screen.getByRole('button', { name: 'WebGL2 없음' }));
    const notice = screen.getByRole('status');
    expect(notice).toHaveTextContent('이 브라우저에서는 지도를 그릴 수 없습니다. 천하 그림만 보입니다.');
    expect(notice).toHaveClass('map-preview-tooltip--pinned');
    // 안내가 있으면 이름표는 거둔다(한 자리에 하나)
    expect(screen.queryByText('위 · 수도')).toBeNull();
  });

  it('구역 수가 bake와 다르면 칠하지 않고 알린다', async () => {
    shared.places = { provinceCount: 3 };
    servePreview(MAP);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    render(<MapPreview serverId="pep" variant="backdrop" />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('세력 색을 칠하지 못했습니다');
    expect(alert).toHaveClass('map-preview-tooltip--pinned');
    expect(shared.topdown!.world).toBeUndefined();
    warn.mockRestore();
  });

  it('bakeId가 없거나 형식이 틀리면 받은 미리보기 그대로 옛 지도판(미리보기를 다시 받지 않는다)', async () => {
    servePreview({ ...MAP, topdownBakeId: undefined });
    const { unmount } = render(<MapPreview serverId="pep" variant="backdrop" />);
    await screen.findByTestId('world-map');
    expect(screen.queryByTestId('topdown-map')).toBeNull();
    expect(fetched).toEqual(['/api/server-map/pep']);
    unmount();
    servePreview({ ...MAP, topdownBakeId: 'not-a-bake' });
    render(<MapPreview serverId="pep" variant="backdrop" />);
    await screen.findByTestId('world-map');
    expect(screen.queryByTestId('topdown-map')).toBeNull();
  });

  it('로비 상자(panel) 캡션: 날짜가 없으면 「확인 중」, undefined 를 찍지 않는다', async () => {
    servePreview({ ...MAP, year: undefined, month: undefined });
    const { container } = render(<MapPreview serverId="pep" serverName="pep 1기" />);
    await screen.findByTestId('topdown-map');
    expect(container.querySelector('.map-preview-cap')).toHaveTextContent('pep 1기 · 확인 중');
    expect(container.textContent).not.toContain('undefined');
  });

  it('미리보기를 못 받으면 옛 화면과 같은 안내를 보이고 부르는 쪽에 알린다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 502 })));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const onPreviewError = vi.fn();
    render(<MapPreview serverId="pep" variant="backdrop" onPreviewError={onPreviewError} />);
    // 받는 동안은 「지도를 불러오는 중」 자리 표시가 같은 status 라 글자로 기다린다
    expect(await screen.findByText('지도를 불러오지 못했습니다')).toHaveAttribute('role', 'status');
    expect(onPreviewError).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});

describe('지도 미리보기 — 스위치가 꺼진 빌드(운영)', () => {
  it('서버가 bakeId를 줘도 옛 지도판만 그린다', async () => {
    vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '');
    servePreview(MAP);
    render(<MapPreview serverId="pep" variant="backdrop" mapData={MAP} />);
    await screen.findByTestId('world-map');
    expect(screen.queryByTestId('topdown-map')).toBeNull();
  });
});
