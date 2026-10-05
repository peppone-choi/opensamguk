// @vitest-environment node
// Modal 의 격리는 useLayoutEffect 다(K5 10-05 후속). 처음부터 열린 채 서버에서 그려도 경고 없이 그려져야 한다 —
// 경고가 나면 isomorphic(서버에선 useEffect) 처리가 필요하다는 신호다.
import { renderToString } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Modal } from '../Modal';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Modal 서버 렌더', () => {
  it('열린 Modal 을 서버에서 그려도 경고 · 오류가 없다', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const html = renderToString(<Modal ariaLabel="확인" onClose={() => {}}><p>내용</p></Modal>);
    expect(html).toContain('role="dialog"');
    expect(error).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
  });
});
