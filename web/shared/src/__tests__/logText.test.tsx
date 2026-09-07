import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LogText, logPlainText, parseLogTokens } from '../index';

describe('parseLogTokens', () => {
  it('splits devsam color tokens into segments and keeps the closer semantics', () => {
    expect(parseLogTokens('<C>●</>187년 1월:첫 기록')).toEqual([
      { text: '●', tone: 'C' },
      { text: '187년 1월:첫 기록' },
    ]);
    expect(parseLogTokens('<Y>ⓝ하진</>이 <M>필사즉생</>을 발동')).toEqual([
      { text: 'ⓝ하진', tone: 'Y' },
      { text: '이 ' },
      { text: '필사즉생', tone: 'M' },
      { text: '을 발동' },
    ]);
  });

  it('handles <X1>, <1>, <b>, ev_failed and explicit hex colors like the legacy formatter', () => {
    expect(parseLogTokens('<R1>경고</> <1>작게</> <b>굵게</b>')).toEqual([
      { text: '경고', tone: 'R', small: true },
      { text: ' ' },
      { text: '작게', small: true },
      { text: ' ' },
      { text: '굵게', bold: true },
    ]);
    expect(parseLogTokens("<span class='ev_failed'>실패</span> <span style=\"color:#abc\">색</span>")).toEqual([
      { text: '실패', failed: true },
      { text: ' ' },
      { text: '색', color: '#abc' },
    ]);
  });

  it('never treats unknown markup as HTML and tolerates unbalanced closers', () => {
    expect(parseLogTokens('<script>x</script></>남음<Y>열림')).toEqual([
      { text: '<script>x</script>남음' },
      { text: '열림', tone: 'Y' },
    ]);
    expect(parseLogTokens('')).toEqual([]);
    expect(parseLogTokens(null)).toEqual([]);
    expect(logPlainText('<C>●</>187년 1월:새 기록')).toBe('●187년 1월:새 기록');
  });
});

describe('LogText', () => {
  it('renders tokens as palette spans without innerHTML', () => {
    const { container } = render(<LogText text="<C>●</>187년 1월:<Y>관우</>의 <span style='color:#abc'>동향</span>" data-testid="line" />);
    const line = screen.getByTestId('line');
    expect(line.textContent).toBe('●187년 1월:관우의 동향');
    expect(container.querySelector('.os-log__C')).toHaveTextContent('●');
    expect(container.querySelector('.os-log__Y')).toHaveTextContent('관우');
    expect((container.querySelector('span[style]') as HTMLElement).style.color).toBe('rgb(170, 187, 204)');
    expect(container.querySelector('script')).toBeNull();
  });

  it('keeps hostile text inert', () => {
    const { container } = render(<LogText text='<img src=x onerror="alert(1)">글' />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toBe('<img src=x onerror="alert(1)">글');
  });
});
