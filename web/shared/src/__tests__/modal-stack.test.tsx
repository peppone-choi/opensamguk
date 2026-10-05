// 겹친 Modal(K10 2026-10-05 품질 측정 #1) — 확인 창을 연 채 결과 창이 열리고 닫히면 화면 전체가 inert · aria-hidden
// 으로 남던 결함. K10 재현(reports/opensamguk/evidence/2026-10-05-k10-quality/modal-stack-repro.test.tsx)에 동시에
// 닫힘 · 맨 위만 Esc 를 더했다. 격리는 전역 스택 하나가 맡는다 — 맨 위 Modal 기준으로 다시 걸고, 마지막이 닫힐 때만 푼다.
import { fireEvent, render } from '@testing-library/react';
import { useLayoutEffect } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Modal } from '../Modal';

function Screen({ b, c, onB = () => {}, onC = () => {} }: { b: boolean; c: boolean; onB?: () => void; onC?: () => void }) {
  return (
    <div>
      <main data-testid="page"><button type="button">page action</button></main>
      {c && <Modal ariaLabel="C result" onClose={onC}><p>result</p></Modal>}
      {b && <Modal ariaLabel="B confirm" onClose={onB}><p>confirm</p></Modal>}
    </div>
  );
}
const state = (el: Element | null) => ({
  ariaHidden: el?.getAttribute('aria-hidden') ?? null,
  inert: (el as (HTMLElement & { inert?: boolean }) | null)?.inert ?? false,
});
const FREE = { ariaHidden: null, inert: false };
const ISOLATED = { ariaHidden: 'true', inert: true };
const overlayOf = (label: string) => document.querySelector(`[role="dialog"][aria-label="${label}"]`)?.parentElement ?? null;

afterEach(() => {
  document.body.style.overflow = '';
});

describe('겹친 Modal — 격리 복원(전역 스택)', () => {
  it('대조: B 가 닫힌 뒤 C 가 열리고 닫히면 화면이 풀린다', () => {
    const { rerender, getByTestId } = render(<Screen b c={false} />);
    rerender(<Screen b={false} c={false} />);
    rerender(<Screen b={false} c />);
    rerender(<Screen b={false} c={false} />);
    expect(state(getByTestId('page'))).toEqual(FREE);
  });

  it('겹침: B 가 열린 채 C 가 열리고 → B 닫힘 → C 닫힘 → 화면이 풀린다', () => {
    const { rerender, getByTestId } = render(<Screen b c={false} />);
    rerender(<Screen b c />);
    rerender(<Screen b={false} c />);
    rerender(<Screen b={false} c={false} />);
    expect(state(getByTestId('page'))).toEqual(FREE);
  });

  it('겹침 중 아래 창이 먼저 닫혀도 위 창이 열린 동안 화면은 막혀 있다', () => {
    const { rerender, getByTestId } = render(<Screen b c={false} />);
    rerender(<Screen b c />);
    rerender(<Screen b={false} c />);
    expect(state(getByTestId('page'))).toEqual(ISOLATED);
  });

  it('동시에 닫힘: 둘이 한 번에 닫혀도 화면이 풀리고 스크롤이 돌아온다', () => {
    document.body.style.overflow = 'scroll';
    const { rerender, getByTestId } = render(<Screen b c={false} />);
    rerender(<Screen b c />);
    expect(document.body.style.overflow).toBe('hidden');
    rerender(<Screen b={false} c={false} />);
    expect(state(getByTestId('page'))).toEqual(FREE);
    expect(document.body.style.overflow).toBe('scroll');
  });

  it('동시에 닫힘(열린 순서 반대): C 가 먼저 열리고 B 가 위에 열린 뒤 한 번에 닫혀도 풀린다', () => {
    const { rerender, getByTestId } = render(<Screen b={false} c />);
    rerender(<Screen b c />);
    rerender(<Screen b={false} c={false} />);
    expect(state(getByTestId('page'))).toEqual(FREE);
  });

  it('위 창이 열린 동안 아래 창도 막히고, 위 창이 닫히면 아래 창이 다시 풀린다', () => {
    const { rerender } = render(<Screen b c={false} />);
    rerender(<Screen b c />); // C 가 나중에 열렸다 — 맨 위는 C
    expect(state(overlayOf('B confirm'))).toEqual(ISOLATED);
    expect(state(overlayOf('C result'))).toEqual(FREE);
    rerender(<Screen b c={false} />);
    expect(state(overlayOf('B confirm'))).toEqual(FREE);
  });

  it('Esc 는 맨 위 창만 닫는다', () => {
    const onB = vi.fn();
    const onC = vi.fn();
    const { rerender } = render(<Screen b c={false} onB={onB} onC={onC} />);
    rerender(<Screen b c onB={onB} onC={onC} />); // 맨 위는 C
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onC).toHaveBeenCalledTimes(1);
    expect(onB).not.toHaveBeenCalled();
  });
});

/**
 * 닫힌 그 커밋 안에서 본 화면 상태. 탐침의 layout effect 는 같은 커밋에서 Modal 정리(삭제 단계) 뒤에 돈다 — 격리 해제가
 * passive effect 정리였다면 여기서는 아직 aria-hidden · overflow:hidden 이 남아 있다(K5 발견, gateway community-post 시험).
 * 그 틈에 새로 뜬 status 줄은 숨은 조상 밑이라 화면 낭독기 · 시험에서 안 보였다.
 */
function ClosedCommitProbe({ open, seen }: { open: boolean; seen: Array<{ readonly hidden: number; readonly overflow: string }> }) {
  useLayoutEffect(() => {
    if (!open) seen.push({ hidden: document.querySelectorAll('[aria-hidden="true"]').length, overflow: document.body.style.overflow });
  }, [open, seen]);
  return null;
}

function ProbeScreen({ b, c, seen }: { b: boolean; c: boolean; seen: Array<{ readonly hidden: number; readonly overflow: string }> }) {
  return (
    <div>
      <main data-testid="page">
        <button type="button">page action</button>
        {!b && !c ? <p role="status">처리했습니다</p> : null}
      </main>
      {c && <Modal ariaLabel="C result" onClose={() => {}}><p>result</p></Modal>}
      {b && <Modal ariaLabel="B confirm" onClose={() => {}}><p>confirm</p></Modal>}
      <ClosedCommitProbe open={b || c} seen={seen} />
    </div>
  );
}

describe('닫힌 커밋 안에서 바로 풀린다(layout 시점)', () => {
  it('하나가 닫힌 같은 틱에 aria-hidden 0 · 스크롤 복원 — 새 status 줄이 바로 보인다', () => {
    const seen: Array<{ readonly hidden: number; readonly overflow: string }> = [];
    const { rerender } = render(<ProbeScreen b c={false} seen={seen} />);
    rerender(<ProbeScreen b={false} c={false} seen={seen} />);
    expect(seen).toEqual([{ hidden: 0, overflow: '' }]);
  });

  it('겹친 둘이 한 번에 닫힌 같은 틱에도 aria-hidden 0 · 스크롤 복원', () => {
    const seen: Array<{ readonly hidden: number; readonly overflow: string }> = [];
    const { rerender } = render(<ProbeScreen b c={false} seen={seen} />);
    rerender(<ProbeScreen b c seen={seen} />);
    rerender(<ProbeScreen b={false} c={false} seen={seen} />);
    expect(seen).toEqual([{ hidden: 0, overflow: '' }]);
  });
});
