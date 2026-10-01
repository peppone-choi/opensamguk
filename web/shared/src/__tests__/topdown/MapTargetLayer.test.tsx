import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useTargetPicker } from '../../parts/MapTargetPicker';
import type { TargetCandidate, TargetKind } from '../../parts/types';
import { MapTargetLayer } from '../../map/topdown/MapTargetLayer';
import type { Camera } from '../../map/topdown/types';

// 보드 V31SystemMapPick: 후보 44 표지(단추) · 이름표, 후보 밖 흐리게, 고른 곳까지 점선 + 거리. 상태 · 차례는 K3 picker 가 정본.
// 화면 400×300, 카메라 가운데 (100,100) · 칸당 16px → 칸 (c,r) 가운데는 x = (c+0.5−100)·16+200, y = (r+0.5−100)·16+150.
const CAMERA: Camera = { center: { col: 100, row: 100 }, zoom: 16 };

const cand = (targetId: string, col: number, row: number, extra: Partial<TargetCandidate> = {}): TargetCandidate => ({
  targetKind: 'place', targetId, name: targetId, available: true, cell: { col, row }, ...extra,
});

function Harness({ candidates, kind = 'place', onBlocked, from }: {
  candidates: TargetCandidate[]; kind?: TargetKind; onBlocked?: (c: TargetCandidate) => void; from?: { col: number; row: number } | null;
}) {
  const picker = useTargetPicker({ kind, candidates, onCancel: () => undefined });
  return <div style={{ position: 'relative' }}>
    <MapTargetLayer camera={CAMERA} candidates={candidates} picker={picker} onBlocked={onBlocked} from={from ?? null} />
    <output data-testid="selected">{picker.selected.join(',')}</output>
  </div>;
}

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, left: 0, top: 0, right: 400, bottom: 300, width: 400, height: 300, toJSON: () => ({}) });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('MapTargetLayer', () => {
  it('후보마다 44 표지를 칸 가운데에 두고, 이름 · 상태를 읽는 단추다. 화면 밖 · 칸 없는 후보는 목록 몫으로 센다', () => {
    const { container } = render(<Harness candidates={[
      cand('허현', 100, 100), cand('영음현', 102, 99, { available: false, reason: '이웃이 아닙니다' }),
      cand('먼곳', 140, 100), { targetKind: 'place', targetId: '칸없음', name: '칸없음', available: true },
    ]} />);
    const here = screen.getByRole('button', { name: '허현 — 고를 수 있음' });
    expect(here.style.left).toBe('208px');
    expect(here.style.top).toBe('158px');
    expect(here.style.width).toBe('44px');
    expect(here.style.height).toBe('44px');
    expect(screen.getByRole('button', { name: '영음현 — 고를 수 없음 — 누르면 이유' })).toHaveAttribute('data-target-state', 'no');
    expect(screen.queryByRole('button', { name: /먼곳/ })).toBeNull();
    expect(container.querySelector('[data-map-targets]')).toHaveAttribute('data-offscreen-count', '2');
  });

  it('고를 수 있는 표지를 누르면 고르고, 못 고르는 표지는 고르지 않고 사유를 열게 넘긴다', () => {
    const blocked = vi.fn();
    render(<Harness candidates={[cand('허현', 100, 100), cand('영음현', 102, 99, { available: false })]} onBlocked={blocked} />);
    fireEvent.click(screen.getByRole('button', { name: /영음현/ }));
    expect(blocked).toHaveBeenCalledWith(expect.objectContaining({ targetId: '영음현' }));
    expect(screen.getByTestId('selected')).toHaveTextContent('');
    fireEvent.click(screen.getByRole('button', { name: '허현 — 고를 수 있음' }));
    expect(screen.getByTestId('selected')).toHaveTextContent('허현');
    expect(screen.getByRole('button', { name: '허현 — 고른 곳' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('고른 곳까지 금색 점선과 거리 꼬리표', () => {
    const { container } = render(<Harness from={{ col: 98, row: 100 }}
      candidates={[cand('허현', 100, 100, { distanceCells: 2, distanceTurns: 1 })]} />);
    expect(container.querySelector('path')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /허현/ }));
    expect(container.querySelector('path')).toHaveAttribute('d', 'M176 158 L208 158');
    expect(container.querySelector('[data-target-distance]')).toHaveTextContent('2칸 · 1순');
  });

  it('여러 현 고르기는 고른 차례 번호, 군단 고르기는 둥근 표지에 첫 글자', () => {
    const { unmount } = render(<Harness kind="multi-county" candidates={[cand('가', 100, 100), cand('나', 101, 100)]} />);
    fireEvent.click(screen.getByRole('button', { name: /^나/ }));
    fireEvent.click(screen.getByRole('button', { name: /^가/ }));
    expect(screen.getByRole('button', { name: /^나/ })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: /^가/ })).toHaveTextContent('2');
    unmount();
    render(<Harness kind="corps" candidates={[cand('하후돈 군단', 100, 100, { targetKind: 'corps' })]} />);
    const corps = screen.getByRole('button', { name: /하후돈 군단/ });
    expect(corps).toHaveTextContent('하');
    expect(corps.style.borderRadius).toBe('50%');
  });

  it('카메라를 모르면 표지를 그리지 않는다(흐림만)', () => {
    function NoCamera() {
      const picker = useTargetPicker({ kind: 'place', candidates: [cand('허현', 100, 100)], onCancel: () => undefined });
      return <MapTargetLayer camera={null} candidates={[cand('허현', 100, 100)]} picker={picker} />;
    }
    render(<NoCamera />);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });
});
