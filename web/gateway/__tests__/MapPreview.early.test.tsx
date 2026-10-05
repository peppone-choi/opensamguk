import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 첫 그림(M1-5): 미리보기 요청을 하이드레이션 전에 시작한다. 서버 HTML 의 받는 중 자리가 서버 id 를 싣고(data-map-preview-server),
// 모듈이 평가될 때 그 자리를 찾아 받기 시작하며, 효과는 같은 약속을 한 번만 이어받는다.
// 받은 미리보기에 城이 없으면 「못 불러옴」에서 멈춘다 — 옛 지도 · 새 지도 그리기 없이 요청 수만 본다.
const EMPTY = { serverName: 's', year: 200, month: 1, mapCode: 'han-world-v3', width: 1, height: 1, cities: [], nations: [] };
let fetched: string[] = [];

beforeEach(() => {
  vi.resetModules();
  fetched = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    fetched.push(url);
    return new Response(JSON.stringify(EMPTY), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
  document.body.innerHTML = '';
});
afterEach(() => {
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('미리보기를 하이드레이션 전에 받기(M1-5)', () => {
  it('받는 중 자리가 서버 HTML 에 있으면 모듈 평가 때 받기 시작하고, 효과는 그 약속을 이어받는다(요청 한 번)', async () => {
    document.body.innerHTML = '<div data-map-preview-server="pep"></div>';
    const { default: MapPreview } = await import('@/components/MapPreview');
    expect(fetched).toEqual(['/api/server-map/pep']);
    document.body.innerHTML = '';
    render(<MapPreview serverId="pep" />);
    await screen.findByText('지도를 불러오지 못했습니다');
    expect(fetched).toEqual(['/api/server-map/pep']);
  });

  it('자리가 없으면 모듈 평가 때 받지 않는다 — 효과가 받는다', async () => {
    const { default: MapPreview } = await import('@/components/MapPreview');
    expect(fetched).toEqual([]);
    render(<MapPreview serverId="pep" />);
    await screen.findByText('지도를 불러오지 못했습니다');
    expect(fetched).toEqual(['/api/server-map/pep']);
  });

  it('이어받기는 한 번 — 다시 받기(refreshKey) · 다른 서버는 새로 받는다', async () => {
    document.body.innerHTML = '<div data-map-preview-server="pep"></div>';
    const { default: MapPreview } = await import('@/components/MapPreview');
    document.body.innerHTML = '';
    const { rerender } = render(<MapPreview serverId="pep" refreshKey={0} />);
    await screen.findByText('지도를 불러오지 못했습니다');
    await act(async () => { rerender(<MapPreview serverId="pep" refreshKey={1} />); });
    await waitFor(() => expect(fetched).toEqual(['/api/server-map/pep', '/api/server-map/pep']));
    await act(async () => { rerender(<MapPreview serverId="uni" refreshKey={1} />); });
    await waitFor(() => expect(fetched).toEqual(['/api/server-map/pep', '/api/server-map/pep', '/api/server-map/uni']));
  });

  it('받는 중 자리는 서버 id 를 싣는다(서버 HTML) — 미리 받은 자료(mapData)가 있으면 싣지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));
    const { default: MapPreview } = await import('@/components/MapPreview');
    const { container } = render(<MapPreview serverId="pep" />);
    expect(container.querySelector('[data-map-preview-server="pep"]')).not.toBeNull();
  });
});
