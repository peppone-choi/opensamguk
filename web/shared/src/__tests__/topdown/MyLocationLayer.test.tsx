import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MyLocationLayer, nudgeEdge, placePin, type MyLocationPin } from '../../map/topdown/MyLocationLayer';
import type { Camera } from '../../map/topdown/types';

// 보드 V31SystemMarker: 핀(초상 원형 48 + 국가색 링 3 + 금색 테 + 핀 끝 = 실제 자리), 현 보기만 꼬리표, 화면 밖이면 가장자리 44 × 52 단추 + 거리.
// 화면 400×300, 카메라 가운데 (100,100) · 칸당 16px → 칸 (c,r) 가운데는 x = (c+0.5−100)·16+200, y = (r+0.5−100)·16+150.
const CAMERA: Camera = { center: { col: 100, row: 100 }, zoom: 16 };
// 핀 끝 자리는 연속 칸 좌표(城 발자국 가운데 등) — 칸 (100,101) 가운데 = (100.5, 101.5)
const ME: MyLocationPin = { at: { col: 100.5, row: 101.5 }, state: 'IN_CITY', name: '하후돈', nationColor: '#b03a2e' };

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, left: 0, top: 0, right: 400, bottom: 300, width: 400, height: 300, toJSON: () => ({}) });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('MyLocationLayer', () => {
  it('화면 안이면 핀: 핀 끝이 실제 자리 칸 가운데, 이름 · 자리를 읽는 단추, 초상이 없으면 이름 첫 글자 · 세력 색 링', () => {
    const pick = vi.fn();
    render(<MyLocationLayer camera={CAMERA} level="commandery" me={ME} onPick={pick} />);
    const pin = screen.getByRole('button', { name: '내 위치 — 하후돈, 성 안. 누르면 내 장수 카드' });
    // 칸 (100,101) 가운데 = (208, 174) → 핀 상자 왼쪽 위 = (208−24, 174−62)
    expect(pin.style.left).toBe('184px');
    expect(pin.style.top).toBe('112px');
    expect(pin.style.width).toBe('48px');
    expect(pin).toHaveTextContent('하');
    expect((pin.firstElementChild as HTMLElement).style.border).toContain('rgb(176, 58, 46)');
    expect(pin).not.toHaveTextContent('내 위치 ·'); // 꼬리표는 현 보기에서만
    fireEvent.click(pin);
    expect(pick).toHaveBeenCalledOnce();
  });

  it('현 보기에서는 「내 위치 · 성 안」 꼬리표, 세력이 없으면 링은 무소속 회색(색을 짓지 않는다), 초상이 있으면 아이콘 판 그림', () => {
    render(<MyLocationLayer camera={CAMERA} level="county" me={{ ...ME, nationColor: null, picture: 'sample.jpg', imageServer: 0 }} />);
    const pin = screen.getByRole('button', { name: /^내 위치 — 하후돈/ });
    expect(pin).toHaveTextContent('내 위치 · 성 안');
    expect((pin.firstElementChild as HTMLElement).style.border).toContain('rgb(142, 136, 121)');
    expect(pin.querySelector('img')?.getAttribute('src')).toMatch(/sample/);
  });

  it('화면 밖이면 그 방향 가장자리 「내 위치」 단추 + 거리(칸), 누르면 그리로', () => {
    const go = vi.fn();
    const { container } = render(<MyLocationLayer camera={CAMERA} me={{ ...ME, at: { col: 140.5, row: 100.5 } }} onGo={go} />);
    expect(screen.queryByRole('button', { name: /누르면 내 장수 카드/ })).toBeNull();
    const edge = screen.getByRole('button', { name: '내 위치는 화면 밖 — 41칸, 누르면 그리로' });
    expect(edge).toHaveAttribute('data-edge-side', 'right');
    expect(edge).toHaveTextContent('내 위치41칸');
    expect(Number.parseFloat(edge.style.left) + 44).toBeLessThanOrEqual(400);
    expect(container.querySelector('[data-my-location]')).toHaveAttribute('data-my-location', 'edge');
    fireEvent.click(edge);
    expect(go).toHaveBeenCalledOnce();
  });

  it('화면 틀이 덮은 왼쪽(서랍)은 화면 밖으로 쳐서, 화살표를 덮이지 않은 가장자리에 둔다', () => {
    // 칸 (90,100) 가운데 x = 48 — 서랍 160 밑이다
    const free = render(<MyLocationLayer camera={CAMERA} me={{ ...ME, at: { col: 90.5, row: 100.5 } }} />);
    expect(free.container.querySelector('[data-my-location]')).toHaveAttribute('data-my-location', 'pin');
    free.unmount();
    render(<MyLocationLayer camera={CAMERA} me={{ ...ME, at: { col: 90.5, row: 100.5 } }} edgeInset={{ left: 160 }} />);
    const edge = screen.getByRole('button', { name: /^내 위치는 화면 밖/ });
    expect(edge).toHaveAttribute('data-edge-side', 'left');
    expect(Number.parseFloat(edge.style.left)).toBeGreaterThanOrEqual(160);
  });

  it('대상 고르는 중(inert)에는 표지만 보이고 누르지 않는다(Tab 순서에서도 빠진다)', () => {
    const pick = vi.fn();
    render(<MyLocationLayer camera={CAMERA} me={ME} onPick={pick} inert />);
    const pin = screen.getByRole('button', { name: /누르면 내 장수 카드/ });
    expect(pin.style.pointerEvents).toBe('none');
    expect(pin.tabIndex).toBe(-1);
    fireEvent.click(pin);
    expect(pick).not.toHaveBeenCalled();
  });

  it('서버가 아직 안 주는 자리 상태는 계약판 행으로 남기고, 카메라 · 내 자리를 모르면 그리지 않는다', () => {
    const { container, rerender } = render(<MyLocationLayer camera={CAMERA} me={ME} serverWait="U-04" />);
    expect(container.querySelector('[data-server-wait]')).toHaveAttribute('data-server-wait', 'U-04');
    rerender(<MyLocationLayer camera={null} me={ME} />);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    rerender(<MyLocationLayer camera={CAMERA} me={null} />);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });
});

describe('가장자리 단추는 지도 조작을 피한다', () => {
  it('같은 지도 상자의 조작 단추(data-map-control)에 깔리면 안쪽으로 비킨다(모바일 좁은 지도의 왼쪽 보기 단추)', () => {
    const rect = (left: number, top: number, right: number, bottom: number) => ({ x: left, y: top, left, top, right, bottom, width: right - left, height: bottom - top, toJSON: () => ({}) });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return this.dataset.mapControl ? rect(4, 100, 60, 300) : rect(0, 0, 400, 300);
    });
    // 칸 (70,100) 가운데는 화면 왼쪽 밖 → 왼쪽 가장자리 단추(가운데 x 26)가 보기 단추(4–60) 밑 → 오른쪽으로 60 + 4 + 22 = 86
    render(<div><div data-map-control="view-bar" /><MyLocationLayer camera={CAMERA} me={{ ...ME, at: { col: 70.5, row: 100.5 } }} /></div>);
    const edge = screen.getByRole('button', { name: /^내 위치는 화면 밖/ });
    expect(edge).toHaveAttribute('data-edge-side', 'left');
    expect(edge.style.left).toBe('64px');
  });

  it('비키는 방향: 왼쪽 · 오른쪽은 가로, 위 · 아래는 세로, 겹치지 않으면 그대로, 상자 밖으로는 안 나간다', () => {
    const box = { left: 0, top: 0, right: 400, bottom: 300 };
    const ctrl = [{ left: 340, top: 0, right: 400, bottom: 60 }];
    expect(nudgeEdge({ kind: 'edge', x: 372, y: 30, angle: 0, side: 'right' }, ctrl, box)).toEqual({ x: 314, y: 30 });
    expect(nudgeEdge({ kind: 'edge', x: 370, y: 26, angle: 0, side: 'top' }, ctrl, box)).toEqual({ x: 370, y: 90 });
    expect(nudgeEdge({ kind: 'edge', x: 200, y: 26, angle: 0, side: 'top' }, ctrl, box)).toEqual({ x: 200, y: 26 });
    expect(nudgeEdge({ kind: 'edge', x: 26, y: 290, angle: 0, side: 'bottom' }, [], box)).toEqual({ x: 26, y: 274 });
  });
});

describe('placePin', () => {
  const box = { left: 0, top: 0, right: 400, bottom: 300 };
  it('핀 머리(62)까지 들어오면 핀, 위로 잘리면 위쪽 가장자리 단추', () => {
    expect(placePin({ x: 200, y: 70 }, box).kind).toBe('pin');
    expect(placePin({ x: 200, y: 40 }, box)).toMatchObject({ kind: 'edge', side: 'top' });
  });
});
