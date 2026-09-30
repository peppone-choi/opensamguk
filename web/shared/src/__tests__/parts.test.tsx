import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReasonSheet } from '../ReasonTooltip';
import {
  InputAction,
  MISSING_REASON,
  PART_ICON_SOURCE,
  PeoplePicker,
  PickBar,
  StatusView,
  TargetCandidateList,
  TimeBar,
  layoutEventRows,
  matchesKoreanName,
  useTargetPicker,
  type PersonOption,
  type TargetCandidate,
  type TimeBarEvent,
} from '../parts';

/** v3.1 규칙: 네이티브 disabled 금지 · title 전용 정보 금지. */
function expectNoNativeDisabledOrTitle(root: HTMLElement) {
  expect(root.querySelectorAll('[disabled]')).toHaveLength(0);
  expect(root.querySelectorAll('[title]')).toHaveLength(0);
}

afterEach(() => { vi.useRealTimers(); });

describe('InputAction — 입력 4상태는 서버 값으로만', () => {
  it('원장 행이 없으면(null) 아무것도 그리지 않는다', () => {
    const { container } = render(<InputAction inputId="action.employ" availability={null} label="등용" onAct={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('AVAILABLE 은 보통 단추이고 누르면 onAct, 보내는 중엔 무시한다', () => {
    const onAct = vi.fn();
    const { rerender } = render(
      <InputAction inputId="action.employ" availability={{ inputId: 'action.employ', status: 'AVAILABLE' }} label="등용" onAct={onAct} />,
    );
    const btn = screen.getByRole('button', { name: '등용' });
    expect(btn).toHaveAttribute('data-input-id', 'action.employ');
    fireEvent.click(btn);
    expect(onAct).toHaveBeenCalledTimes(1);
    rerender(<InputAction inputId="action.employ" availability={{ inputId: 'action.employ', status: 'AVAILABLE' }} label="등용" onAct={onAct} busy />);
    fireEvent.click(screen.getByRole('button', { name: '등용' }));
    expect(onAct).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '등용' })).toHaveAttribute('aria-busy', 'true');
  });

  it('BLOCKED 는 점선 + 보이는 사유, 누르면 사유 시트(머리 · 이렇게 하면 됩니다 · 도움말)가 열리고 onAct 는 안 불린다', () => {
    const onAct = vi.fn();
    const onHelp = vi.fn();
    const { container } = render(
      <InputAction
        inputId="court.dispatch"
        availability={{ inputId: 'court.dispatch', status: 'BLOCKED', code: 'NOT_RULER', reason: '주공만 할 수 있습니다' }}
        label="발령"
        onAct={onAct}
        reasonTitle="발령은 주공만 할 수 있습니다."
        recovery="주공이 되려면 거병하거나 독립해야 합니다."
        recoveryDraft
        helpTopic={{ id: 'input:court.dispatch!NOT_RULER', title: '발령' }}
        onHelp={onHelp}
      />,
    );
    const btn = screen.getByRole('button', { name: '발령' });
    expect(btn).toHaveAttribute('aria-disabled', 'true');
    expect(btn).toHaveAttribute('data-input-status', 'BLOCKED');
    expect(btn).toHaveAccessibleDescription(/주공만 할 수 있습니다/);
    expect(container.querySelector('.os-ia__why')).toHaveTextContent('주공만 할 수 있습니다');
    expect(container.querySelector('[data-reason-code="NOT_RULER"]')).not.toBeNull();
    expectNoNativeDisabledOrTitle(container);

    fireEvent.click(btn);
    expect(onAct).not.toHaveBeenCalled();
    const sheet = screen.getByRole('dialog', { name: '발령은 주공만 할 수 있습니다.' });
    expect(sheet).toBeVisible();
    expect(within(sheet).getByText('주공이 되려면 거병하거나 독립해야 합니다.')).toBeInTheDocument();
    expect(within(sheet).getByText('초안')).toBeInTheDocument();
    fireEvent.click(within(sheet).getByRole('link', { name: '도움말 — 발령 →' }));
    expect(onHelp).toHaveBeenCalledWith('input:court.dispatch!NOT_RULER');
  });

  it('BLOCKED 인데 서버 사유가 없으면 지어내지 않고 「사유를 받지 못했습니다」', () => {
    render(<InputAction inputId="x" availability={{ inputId: 'x', status: 'BLOCKED' }} label="하기" onAct={vi.fn()} />);
    expect(screen.getByRole('button', { name: '하기' })).toHaveAccessibleDescription(MISSING_REASON);
  });

  it('제출이 거절돼 AVAILABLE → BLOCKED 로 바뀌면 reasonDefaultOpen 으로 서버 사유가 열린 채로 뜬다', () => {
    const { rerender } = render(<InputAction inputId="action.move" availability={{ inputId: 'action.move', status: 'AVAILABLE' }} label="이동" onAct={vi.fn()} reasonDefaultOpen />);
    expect(screen.queryByRole('tooltip', { hidden: true })).toBeNull();
    rerender(<InputAction inputId="action.move" availability={{ inputId: 'action.move', status: 'BLOCKED', code: 'NO_ROUTE', reason: '갈 길이 없음' }} label="이동" onAct={vi.fn()} reasonDefaultOpen />);
    expect(screen.getByText('갈 길이 없음', { selector: '.os-reason__body' })).toBeVisible();
  });

  it('NOT_DELIVERED 는 점선 + 「준비 중」', () => {
    const { container } = render(
      <InputAction inputId="work.reduce" availability={{ inputId: 'work.reduce', status: 'NOT_DELIVERED' }} label="성방 허물기" onAct={vi.fn()} />,
    );
    expect(screen.getByRole('button', { name: '성방 허물기' })).toHaveAttribute('aria-disabled', 'true');
    expect(container.querySelector('.os-ia__why')).toHaveTextContent('준비 중');
  });
});

describe('ReasonSheet', () => {
  it('함수 자식이면 사유 id 를 받아 원하는 요소에 붙인다', () => {
    render(<ReasonSheet reason="갈 길이 없음">{(id) => <button type="button" aria-describedby={id}>신정현</button>}</ReasonSheet>);
    expect(screen.getByRole('button', { name: '신정현' })).toHaveAccessibleDescription('갈 길이 없음');
  });
});

describe('StatusView — 빈 ≠ 실패', () => {
  it('빈 것은 이유 + 채우는 법, 실패는 alert + 다시 시도 + 오류 번호로 서로 다른 모양이다', () => {
    const onRetry = vi.fn();
    const { container, unmount } = render(
      <StatusView kind="empty" title="지금 잡아 둔 포로가 없습니다" body="전투에서 이기면 포로를 잡을 수 있습니다." actions={<a href="?help=topic:captive">도움말 — 포로</a>} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('지금 잡아 둔 포로가 없습니다');
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(container.querySelector('[data-part-icon="list"]')).not.toBeNull();
    unmount();

    render(<StatusView kind="error" title="창고망을 불러오지 못했습니다" errorCode="E-1234" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('잠시 뒤 다시 해 보세요');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '오류 번호 E-1234 복사' })).toBeInTheDocument();
  });

  it('불러오기 뼈대는 0.3초가 지나야 보인다', () => {
    vi.useFakeTimers();
    const { container } = render(<StatusView kind="loading" rows={2} />);
    expect(container.querySelectorAll('.os-status__skel-row')).toHaveLength(0);
    act(() => { vi.advanceTimersByTime(301); });
    expect(container.querySelectorAll('.os-status__skel-row')).toHaveLength(2);
  });

  it('권한 없음은 「이렇게 하면 됩니다」, 서버 대기 A 는 「준비 중」 칩, 끊김은 마지막 자료 시각과 다시 잇기', () => {
    const { unmount } = render(<StatusView kind="denied" title="발령은 주공만 할 수 있습니다" howTo="거병하거나 독립해야 합니다." helpTopic={{ id: 'topic:dispatch', title: '발령' }} />);
    expect(screen.getByText('이렇게 하면 됩니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '도움말 — 발령' })).toHaveAttribute('href', '?help=topic%3Adispatch');
    unmount();
    const { unmount: u2 } = render(<StatusView kind="waiting" title="외교 관계를 아직 볼 수 없습니다" />);
    expect(screen.getByText('준비 중')).toBeInTheDocument();
    u2();
    const onReconnect = vi.fn();
    render(<StatusView kind="stale" lastReceived="3월 중순 21:40" onReconnect={onReconnect} />);
    expect(screen.getByText(/3월 중순 21:40/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '지금 다시 잇기' }));
    expect(onReconnect).toHaveBeenCalled();
  });
});

describe('matchesKoreanName — 이름 · 초성 찾기', () => {
  it.each([
    ['순욱', 'ㅅㅇ', true],
    ['순욱', '순', true],
    ['허저', 'ㅎㅈ', true],
    ['하후돈', 'ㅎㄷ', true],
    ['하후돈', '후ㄷ', true],
    ['하후돈', 'ㅎㅎ', true],
    ['하후돈', 'ㄷㅎ', false],
    ['장비', '장ㅂ', true],
    ['장비', '관', false],
    ['순 욱', 'ㅅㅇ', true],
  ])('%s ← %s = %s', (name, query, expected) => {
    expect(matchesKoreanName(name, query)).toBe(expected);
  });
});

const CANDS: TargetCandidate[] = [
  { targetKind: 'place', targetId: 'c3', name: '밀현', sub: '하남윤', cell: { col: 3, row: 1 }, available: true, distanceCells: 3, groups: ['이웃'] },
  { targetKind: 'place', targetId: 'c1', name: '영양현', sub: '영천군', cell: { col: 1, row: 1 }, available: true, distanceCells: 1, groups: ['내 영지'] },
  { targetKind: 'place', targetId: 'c2', name: '신정현', sub: '하남윤', cell: { col: 2, row: 2 }, available: false, reasonCode: 'NO_ROUTE', reason: '갈 길이 없음', distanceCells: 2, groups: ['이웃'] },
  { targetKind: 'place', targetId: 'c9', name: '허현', available: false, reason: '갈 수 없는 곳' },
];

function Picker({ kind = 'place' as const, onCancel = vi.fn() }: { kind?: 'place' | 'multi-county'; onCancel?: () => void }) {
  const picker = useTargetPicker({ kind, candidates: kind === 'multi-county' ? CANDS.map((c) => ({ ...c, targetKind: 'multi-county' as const })) : CANDS, onCancel });
  return (
    <>
      <PickBar title="갈 곳 고르기 — 이동 · 04순" hint="지도를 누르거나 오른쪽 목록에서" counts={picker.counts} onCancel={picker.cancel} />
      <TargetCandidateList picker={picker} candidates={CANDS} groups={['내 영지', '이웃']} />
      <output data-testid="sel">{picker.selected.join(',')}</output>
      <output data-testid="state">{CANDS.map((c) => `${c.targetId}:${picker.markerStateOf(c.targetId)}`).join(' ')}</output>
    </>
  );
}

describe('MapTargetPicker — 가능 · 불가를 같이, 지도와 목록이 같은 상태', () => {
  it('가까운 순, 칸 없는 후보는 끝, 띠는 가능 · 불가 수를 센다', () => {
    const { container } = render(<Picker />);
    const names = screen.getAllByRole('option').map((o) => o.querySelector('.os-opt__name')?.textContent);
    expect(names).toEqual(['영양현', '신정현', '밀현', '허현']);
    expect(screen.getByText('지도를 누르거나 오른쪽 목록에서 · 가능 2 · 불가 2')).toBeInTheDocument();
    expectNoNativeDisabledOrTitle(container);
  });

  it('불가 행은 행 전체가 사유 단추이고 고르지 않는다 — 가능 행은 고른다', () => {
    render(<Picker />);
    const blocked = screen.getByRole('option', { name: /신정현/ });
    expect(blocked).toHaveAttribute('aria-disabled', 'true');
    expect(blocked).toHaveAccessibleDescription(/갈 길이 없음/);
    fireEvent.click(blocked);
    expect(screen.getByTestId('sel')).toHaveTextContent('');
    expect(screen.getByRole('dialog', { name: '신정현 — 고를 수 없습니다' })).toBeVisible();
    fireEvent.click(screen.getByRole('option', { name: /영양현/ }));
    expect(screen.getByTestId('sel')).toHaveTextContent('c1');
    expect(screen.getByTestId('state')).toHaveTextContent('c3:ok c1:selected c2:no c9:no');
  });

  it('묶음 탭 · 「가능만」(기본 꺼짐)으로 거른다', () => {
    render(<Picker />);
    expect(screen.getByRole('checkbox', { name: '가능만' })).not.toBeChecked();
    fireEvent.click(screen.getByRole('radio', { name: '이웃' }));
    expect(screen.getAllByRole('option')).toHaveLength(2);
    fireEvent.click(screen.getByRole('checkbox', { name: '가능만' }));
    expect(screen.getAllByRole('option').map((o) => o.querySelector('.os-opt__name')?.textContent)).toEqual(['밀현']);
  });

  it('여러 곳 고르기는 고른 순서 번호를 매기고 다시 누르면 뺀다', () => {
    render(<Picker kind="multi-county" />);
    fireEvent.click(screen.getByRole('option', { name: /밀현/ }));
    fireEvent.click(screen.getByRole('option', { name: /영양현/ }));
    expect(screen.getByTestId('sel')).toHaveTextContent('c3,c1');
    fireEvent.click(screen.getByRole('option', { name: /밀현/ }));
    expect(screen.getByTestId('sel')).toHaveTextContent('c1');
  });

  it('Esc 와 「그만 고르기」는 onCancel', () => {
    const onCancel = vi.fn();
    render(<Picker onCancel={onCancel} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: /그만 고르기/ }));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });
});

const PEOPLE: PersonOption[] = [
  { generalId: 1, name: '순욱', isHuman: false, nation: { id: 1, name: '조조', color: '#4f7fbf' }, location: '허창', groups: ['mine', 'nation'] },
  { generalId: 2, name: '허저', isHuman: true, nation: { id: 1, name: '조조', color: '#4f7fbf' }, location: null, groups: ['nation'] },
  { generalId: 3, name: '원소', nation: { id: 2, name: '원소', color: '#c96b5d' }, location: '업', groups: ['rulers'], blockedReason: '다른 세력 군주에게는 보낼 수 없습니다' },
  { generalId: 4, name: '장비', groups: [] },
];

function People({ multiple = false, groups }: { multiple?: boolean; groups?: ('mine' | 'nation' | 'rulers' | 'all')[] }) {
  const [one, setOne] = useState<number | null>(null);
  const [many, setMany] = useState<readonly number[]>([2]);
  return multiple
    ? <PeoplePicker multiple selected={many} onChange={setMany} load={{ state: 'ready', people: PEOPLE }} groups={groups} />
    : <PeoplePicker selected={one} onChange={setOne} load={{ state: 'ready', people: PEOPLE }} groups={groups} />;
}

describe('PeoplePicker — NPC 포함, 모르는 것은 그리지 않는다', () => {
  it('묶음 탭 수를 세고, 사람 장수만 「사람」 칩, 서버가 안 준 소속 · 자리는 그리지 않는다', () => {
    const { container } = render(<People />);
    expect(screen.getByRole('radio', { name: '내 부 1' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '전체 4' })).toHaveAttribute('aria-checked', 'true');
    expect(within(screen.getByRole('option', { name: /허저/ })).getByText('사람')).toBeInTheDocument();
    expect(within(screen.getByRole('option', { name: /순욱/ })).queryByText('사람')).toBeNull();
    expect(screen.getByRole('option', { name: /허저/ })).toHaveTextContent('자리 모름(시야 밖)');
    const zhang = screen.getByRole('option', { name: /장비/ });
    expect(zhang).not.toHaveTextContent('재야');
    expect(zhang.querySelector('.os-opt__sub')).toBeNull();
    expectNoNativeDisabledOrTitle(container);
  });

  it('초성으로 찾고, 찾기 없음은 빈 묶음과 다른 글이다', () => {
    render(<People />);
    fireEvent.change(screen.getByRole('searchbox', { name: '이름 · 초성으로 찾기' }), { target: { value: 'ㅅㅇ' } });
    expect(screen.getAllByRole('option')).toHaveLength(1);
    fireEvent.change(screen.getByRole('searchbox', { name: '이름 · 초성으로 찾기' }), { target: { value: '관우' } });
    expect(screen.getByText('「관우」와 맞는 사람이 없습니다')).toBeInTheDocument();
  });

  it('고를 수 없는 사람은 행 전체가 사유 단추이고 고르지 않는다', () => {
    render(<People />);
    const yuan = screen.getByRole('option', { name: /원소/ });
    expect(yuan).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(yuan);
    expect(yuan).toHaveAttribute('aria-selected', 'false');
    fireEvent.click(screen.getByRole('option', { name: /순욱/ }));
    expect(screen.getByRole('option', { name: /순욱/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('groups=["all"] 이면 탭 줄을 그리지 않는다(모르는 것을 0 이라 하지 않는다)', () => {
    render(<People groups={['all']} />);
    expect(screen.queryByRole('radiogroup', { name: '묶음' })).toBeNull();
    expect(screen.getAllByRole('option')).toHaveLength(4);
  });

  it('여러 명 — 고른 칩을 44 단추로 빼고, 모두 풀기', () => {
    render(<People multiple />);
    expect(screen.getByText('고른 사람').parentElement).toHaveTextContent('고른 사람 1');
    fireEvent.click(screen.getByRole('option', { name: /순욱/ }));
    fireEvent.click(screen.getByRole('button', { name: '허저 빼기' }));
    expect(screen.getByRole('option', { name: /순욱/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('option', { name: /허저/ })).toHaveAttribute('aria-selected', 'false');
    fireEvent.click(screen.getByRole('button', { name: '모두 풀기' }));
    expect(screen.getByText('고른 사람').parentElement).toHaveTextContent('고른 사람 0');
  });

  it('불러오기 실패는 빈 목록과 다르게 alert + 다시 시도', () => {
    const onRetry = vi.fn();
    render(<PeoplePicker selected={null} onChange={vi.fn()} load={{ state: 'error', onRetry }} label="받는 사람" />);
    expect(screen.getByRole('alert')).toHaveTextContent('받는 사람을 불러오지 못했습니다');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalled();
  });
});

const EVENTS: TimeBarEvent[] = [
  { id: 'a', at: 12_000, label: '부딪힘', tone: 'moss' },
  { id: 'b', at: 13_000, label: '계책', tone: 'info' },
  { id: 'c', at: 60_000, label: '일기토' },
  { id: 'd', at: 90_000, label: '성문', tone: 'rust' },
];

describe('TimeBar', () => {
  it('누를 영역 44 가 겹치는 사건 표식은 다음 줄로 내린다', () => {
    const { rows, count } = layoutEventRows(EVENTS, 100_000, 400);
    expect(rows.get('a')).toBe(0);
    expect(rows.get('b')).toBe(1);
    expect(rows.get('c')).toBe(0);
    expect(count).toBe(2);
  });

  it('다시 보기 — 사건 표식을 누르면 그 순간으로, 다음 사건 · 방향키로 옮긴다', () => {
    const onSeek = vi.fn();
    const { container } = render(
      <TimeBar mode="replay" duration={100_000} position={20_000} events={EVENTS} nowText="우리 선봉 하후돈이 적 좌익과 일기토"
        onSeek={onSeek} playing={false} onPlayPause={vi.fn()} speed={1} onSpeed={vi.fn()} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '성문 — 90% 지점으로' }));
    expect(onSeek).toHaveBeenLastCalledWith(90_000);
    fireEvent.click(screen.getByRole('button', { name: '다음 사건' }));
    expect(onSeek).toHaveBeenLastCalledWith(60_000);
    fireEvent.keyDown(screen.getByRole('slider', { name: '시간' }), { key: 'ArrowRight' });
    expect(onSeek).toHaveBeenLastCalledWith(25_000);
    expect(screen.getByText('0:20 / 1:40')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '1×' })).toHaveAttribute('aria-checked', 'true');
    expectNoNativeDisabledOrTitle(container);
  });

  it('실시간 — 지나간 데까지만 표식을 보이고 「지금으로」', () => {
    const onJumpLive = vi.fn();
    render(<TimeBar mode="live" elapsed={70_000} position={30_000} events={EVENTS} nowText="—" onSeek={vi.fn()} onJumpLive={onJumpLive} />);
    expect(screen.queryByRole('button', { name: /성문/ })).toBeNull();
    expect(screen.getByRole('button', { name: /일기토/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '지금으로' }));
    expect(onJumpLive).toHaveBeenCalled();
  });
});

describe('PartIcon 대체 표', () => {
  it('정본이 아닌 아이콘은 모두 표 한 곳에서 대체로 표시된다', () => {
    const substitutes = Object.entries(PART_ICON_SOURCE).filter(([, s]) => !('sprite' in s) || s.substitute).map(([n]) => n).sort();
    expect(substitutes).toEqual(['alert', 'back', 'clock', 'copy', 'help', 'list', 'lock', 'next', 'pause', 'play', 'prev', 'target', 'unplug']);
  });
});
