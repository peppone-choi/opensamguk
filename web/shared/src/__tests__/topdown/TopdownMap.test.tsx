import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 뒤로 미룬 자료(밉 · 개관 · 장소 · 그림 판)의 실패가 화면 상태로 올라오는지만 본다. GL은 가짜 렌더러로 대신한다.
const fake = vi.hoisted(() => ({ complete: Promise.resolve() as Promise<void>, disposed: 0 }));

vi.mock('../../map/topdown/renderer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../map/topdown/renderer')>();
  class FakeRenderer {
    complete = fake.complete;
    constructor() {
      return new Proxy(this, { get: (target, key) => (key in target ? target[key as keyof FakeRenderer] : () => undefined) });
    }
    load() {
      return Promise.resolve();
    }
    dispose() {
      fake.disposed += 1;
    }
  }
  return { ...actual, TopdownRenderer: FakeRenderer };
});

const { TopdownMap } = await import('../../map/topdown/TopdownMap');

const source = { bakeUrl: '/bake', kitUrl: '/kit' };

describe('TopdownMap 뒤로 미룬 자료', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('장소 표를 받지 못하면 지형이 보여도 오류로 알린다', async () => {
    fake.complete = Promise.reject(new Error('/bake/places.json.gz: HTTP 404'));
    fake.complete.catch(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { container, getByRole } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('error'));
    // 화면에는 쉬운 말 안내만, 서버 원문 · 파일 이름은 콘솔에만
    expect(getByRole('alert')).toHaveTextContent('지도를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.');
    expect(getByRole('alert')).not.toHaveTextContent('places.json.gz');
    expect(warn).toHaveBeenCalledWith('[탑다운 지도] 불러오지 못함', expect.objectContaining({ message: '/bake/places.json.gz: HTTP 404' }));
    warn.mockRestore();
  });

  it('notices={false} 면 안내문은 그리지 않고 상태만 onStatus 로 넘긴다', async () => {
    fake.complete = Promise.reject(new Error('/bake/places.json.gz: HTTP 404'));
    fake.complete.catch(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const seen: string[] = [];
    const { container, queryByRole } = render(<TopdownMap source={source} notices={false} onStatus={(status) => seen.push(status)} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('error'));
    expect(queryByRole('alert')).toBeNull();
    expect(seen).toContain('error');
    warn.mockRestore();
  });

  it('다 받으면 준비 상태로 남는다', async () => {
    fake.complete = Promise.resolve();
    const { container } = render(<TopdownMap source={source} />);
    const box = container.querySelector('[data-map-status]')!;
    await waitFor(() => expect(box.getAttribute('data-map-status')).toBe('ready'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(box.getAttribute('data-map-status')).toBe('ready');
  });
});
