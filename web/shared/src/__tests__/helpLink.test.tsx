import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { HelpLinkProvider } from '../helpLink';
import { ReasonSheet } from '../ReasonTooltip';
import { StatusView } from '../parts/StatusView';

const TOPIC = { id: 'input:court.dispatch!NOT_RULER', title: '발령' };
const keepTab = (id: string) => `/game/pep/court?tab=orders&help=${encodeURIComponent(id)}`;

function Reason(props: { readonly helpHref?: (id: string) => string }) {
  return (
    <ReasonSheet reason="주공만 할 수 있습니다" title="발령은 주공만" helpTopic={TOPIC} defaultOpen helpHref={props.helpHref}>
      <button type="button" aria-disabled="true">발령</button>
    </ReasonSheet>
  );
}

describe('도움말 링크 주소 — HelpLinkProvider', () => {
  it('맥락이 없으면 지금처럼 ?help=<id>', () => {
    render(<Reason />);
    expect(screen.getByRole('link', { name: /도움말 — 발령/ })).toHaveAttribute('href', `?help=${encodeURIComponent(TOPIC.id)}`);
  });

  it('맥락 안에서는 사유 시트 · 권한 없음 상태 둘 다 맥락 주소(지금 쿼리를 지킨다)', () => {
    render(
      <HelpLinkProvider href={keepTab}>
        <Reason />
        <StatusView kind="denied" title="볼 수 없습니다" howTo="주공이 되면 볼 수 있습니다" helpTopic={{ id: 'court', title: '조정' }} />
      </HelpLinkProvider>,
    );
    expect(screen.getByRole('link', { name: /도움말 — 발령/ })).toHaveAttribute('href', keepTab(TOPIC.id));
    expect(screen.getByRole('link', { name: /도움말 — 조정/ })).toHaveAttribute('href', keepTab('court'));
  });

  it('부품에 직접 넘긴 helpHref 가 맥락보다 앞선다', () => {
    render(
      <HelpLinkProvider href={keepTab}>
        <Reason helpHref={(id) => `/help/${id}`} />
      </HelpLinkProvider>,
    );
    expect(screen.getByRole('link', { name: /도움말 — 발령/ })).toHaveAttribute('href', `/help/${TOPIC.id}`);
  });
});
