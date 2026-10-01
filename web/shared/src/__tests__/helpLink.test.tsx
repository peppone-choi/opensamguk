import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HelpLinkProvider } from '../helpLink';
import { ReasonSheet } from '../ReasonTooltip';
import { StatusView } from '../parts/StatusView';

const TOPIC = { id: 'input:court.dispatch!NOT_RULER', title: '발령' };
const keepTab = (id: string) => `/game/pep/court?tab=orders&help=${encodeURIComponent(id)}`;

function Reason(props: { readonly helpHref?: (id: string) => string; readonly onHelp?: (id: string) => void }) {
  return (
    <ReasonSheet reason="주공만 할 수 있습니다" title="발령은 주공만" helpTopic={TOPIC} defaultOpen helpHref={props.helpHref} onHelp={props.onHelp}>
      <button type="button" aria-disabled="true">발령</button>
    </ReasonSheet>
  );
}

const link = (name: RegExp) => screen.getByRole('link', { name });

describe('도움말 링크 — HelpLinkProvider', () => {
  it('맥락이 없으면 지금처럼 ?help=<id> 링크(누르면 브라우저가 간다)', () => {
    render(<Reason />);
    expect(link(/도움말 — 발령/)).toHaveAttribute('href', `?help=${encodeURIComponent(TOPIC.id)}`);
    expect(fireEvent.click(link(/도움말 — 발령/))).toBe(true); // 기본 동작을 막지 않는다
  });

  it('맥락 안에서는 사유 시트 · 권한 없음 둘 다 맥락 주소, 보통 누르기는 open 으로 같은 문서 안에서 연다', () => {
    const open = vi.fn();
    render(
      <HelpLinkProvider value={{ href: keepTab, open }}>
        <Reason />
        <StatusView kind="denied" title="볼 수 없습니다" howTo="주공이 되면 볼 수 있습니다" helpTopic={{ id: 'court', title: '조정' }} />
      </HelpLinkProvider>,
    );
    expect(link(/도움말 — 발령/)).toHaveAttribute('href', keepTab(TOPIC.id));
    expect(link(/도움말 — 조정/)).toHaveAttribute('href', keepTab('court'));
    expect(fireEvent.click(link(/도움말 — 조정/))).toBe(false);
    expect(open).toHaveBeenCalledWith('court');
    expect(fireEvent.click(link(/도움말 — 발령/))).toBe(false);
    expect(open).toHaveBeenLastCalledWith(TOPIC.id);
  });

  it('가운데 · 수정 키 누르기는 브라우저에 맡긴다(새 탭은 쿼리를 지킨 href 로)', () => {
    const open = vi.fn();
    render(<HelpLinkProvider value={{ href: keepTab, open }}><Reason /></HelpLinkProvider>);
    expect(fireEvent.click(link(/도움말 — 발령/), { metaKey: true })).toBe(true);
    expect(fireEvent.click(link(/도움말 — 발령/), { ctrlKey: true })).toBe(true);
    expect(open).not.toHaveBeenCalled();
  });

  it('onHelp 가 있으면 그것이 먼저, 부품에 직접 넘긴 helpHref 는 맥락보다 앞서고 링크로 간다', () => {
    const open = vi.fn();
    const onHelp = vi.fn();
    const { unmount } = render(<HelpLinkProvider value={{ href: keepTab, open }}><Reason onHelp={onHelp} /></HelpLinkProvider>);
    fireEvent.click(link(/도움말 — 발령/));
    expect(onHelp).toHaveBeenCalledWith(TOPIC.id);
    expect(open).not.toHaveBeenCalled();
    unmount();
    render(<HelpLinkProvider value={{ href: keepTab, open }}><Reason helpHref={(id) => `/help/${id}`} /></HelpLinkProvider>);
    expect(link(/도움말 — 발령/)).toHaveAttribute('href', `/help/${TOPIC.id}`);
    expect(fireEvent.click(link(/도움말 — 발령/))).toBe(true);
    expect(open).not.toHaveBeenCalled();
  });
});
