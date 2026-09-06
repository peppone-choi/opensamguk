import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from '../Button';
import { Chip } from '../Chip';
import { ConfirmDialog } from '../ConfirmDialog';
import { EmptyState } from '../EmptyState';
import { Feed, FeedItem } from '../Feed';
import { Flag } from '../Flag';
import { Gauge } from '../Gauge';
import { KV } from '../KV';
import { NavItem } from '../NavItem';
import { Panel, Inset } from '../Panel';
import { PillTabs } from '../PillTabs';
import { ReasonTooltip } from '../ReasonTooltip';
import { SectionHeader } from '../SectionHeader';
import { Slot } from '../Slot';
import { StatRow } from '../StatRow';
import { Tile } from '../Tile';

describe('Button', () => {
  it('renders disabled buttons as dashed with a mandatory reason', () => {
    render(<Button disabled reason="수뇌부 권한 필요">기 밀 실</Button>);
    const btn = screen.getByRole('button', { name: '기 밀 실' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-disabled', 'true');
    expect(btn).toHaveAttribute('title', '수뇌부 권한 필요');
    expect(btn).toHaveClass('os-button--disabled');
  });

  it('keeps variant/size/block classes and the consumer class', () => {
    render(<Button variant="primary" size="sm" block className="mine">실행</Button>);
    expect(screen.getByRole('button', { name: '실행' })).toHaveClass('os-button', 'os-button--primary', 'os-button--sm', 'os-button--block', 'mine');
  });
});

describe('ConfirmDialog', () => {
  it('disables both actions with a reason while busy', () => {
    render(<ConfirmDialog open busy title="서버 리셋" message="정말?" onCancel={() => undefined} onConfirm={() => undefined} />);
    expect(screen.getByRole('button', { name: '취소' })).toHaveAttribute('title', '처리 중입니다');
    expect(screen.getByRole('button', { name: '처리 중…' })).toBeDisabled();
  });
});

describe('primitives', () => {
  it('SectionHeader renders title, sub and tone bar', () => {
    const { container } = render(<SectionHeader title="지난 순" sub="≤15건" tone="rust" actions={<Chip tone="rust">기밀</Chip>} />);
    expect(screen.getByRole('heading', { name: '지난 순' })).toBeInTheDocument();
    expect(container.querySelector('.os-section-header__bar--rust')).toBeInTheDocument();
    expect(screen.getByText('기밀')).toHaveClass('os-chip--rust');
  });

  it('Panel/Inset/EmptyState/Flag/KV render their contracts', () => {
    const { container } = render(
      <Panel frame="rust" aria-label="기밀실"><Inset>안</Inset><EmptyState title="게시물이 없습니다." /><Flag color="#3f6fb5" label="조조" /><KV items={[{ k: '통솔', v: 92 }]} /></Panel>,
    );
    expect(container.querySelector('.os-panel')).toHaveClass('os-frame--rust');
    expect(screen.getByRole('status')).toHaveTextContent('게시물이 없습니다.');
    expect(screen.getByRole('img', { name: '조조' })).toHaveAttribute('data-nation-color', '#3f6fb5');
    expect(screen.getByText('통솔').tagName).toBe('DT');
    expect(screen.getByText('92').tagName).toBe('DD');
  });

  it('StatRow and Gauge expose meter semantics and clamp the bar', () => {
    const { container } = render(<><StatRow label="무력" value={120} max={100} /><Gauge label="민심" value={30} max={100} tone="rust" /></>);
    const meters = screen.getAllByRole('meter');
    expect(meters[0]).toHaveAttribute('aria-valuenow', '120');
    expect(container.querySelector('.os-stat-row__bar > i')).toHaveStyle({ width: '100%' });
    expect(container.querySelector('.os-gauge--rust .os-gauge__bar > i')).toHaveStyle({ width: '30%' });
  });

  it('Feed/Slot render list rows and slot states', () => {
    render(<Feed><FeedItem who="동탁군" what="호뢰관에서 연합군 격퇴" when="3月 상순" /></Feed>);
    expect(screen.getByRole('listitem')).toHaveTextContent('호뢰관에서 연합군 격퇴');
    const { container } = render(<Slot n="1" cmd="휴식" state="now" />);
    expect(container.querySelector('.os-slot')).toHaveClass('os-slot--now');
  });

  it('Tile blocks no/sealed states with a reason and keeps ok clickable', () => {
    const onClick = vi.fn();
    render(<><Tile name="징병" cost="금 200" onClick={onClick} /><Tile name="출병" state="no" reason="병사가 없습니다" /></>);
    fireEvent.click(screen.getByRole('button', { name: /징병/ }));
    expect(onClick).toHaveBeenCalledTimes(1);
    const blocked = screen.getByRole('button', { name: /출병/ });
    expect(blocked).toBeDisabled();
    expect(blocked).toHaveAttribute('title', '병사가 없습니다');
    expect(blocked).toHaveClass('os-tile--no');
  });

  it('PillTabs and NavItem expose selection state', () => {
    const onChange = vi.fn();
    render(<PillTabs label="회의실 탭" tabs={[{ key: 'all', label: '전체' }, { key: 'vote', label: '표결', count: 3 }]} value="all" onChange={onChange} />);
    expect(screen.getByRole('tab', { name: '전체' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('tab', { name: /표결/ }));
    expect(onChange).toHaveBeenCalledWith('vote');
    render(<NavItem href="/game" on>작전실</NavItem>);
    expect(screen.getByRole('link', { name: '작전실' })).toHaveAttribute('aria-current', 'page');
  });

  it('ReasonTooltip reveals the reason on focus', () => {
    render(<ReasonTooltip reason="장수 직위 이상 필요"><button type="button">인 사 부</button></ReasonTooltip>);
    expect(screen.getByRole('tooltip', { hidden: true })).not.toBeVisible();
    fireEvent.focus(screen.getByRole('button', { name: '인 사 부' }).parentElement as HTMLElement);
    expect(screen.getByRole('tooltip')).toBeVisible();
  });
});
