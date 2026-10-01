import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installViewport, type GameEvent } from '@opensamguk/ui';
import RecordsPage from '@/app/game/(campaign)/records/page';
import { deliverTurnCompleted } from '@/lib/turnEvents';

const mock = vi.hoisted(() => ({ get: vi.fn(), mapPreview: vi.fn(), session: { generalId: 7 as number | null, nationId: 1 } }));
vi.mock('@/components/GameShell', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/records/RecordMap', () => ({ default: ({ label }: { label: string }) => <div data-testid="record-map">{label} 지도</div> }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(), usePathname: () => '/game/records' }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({
  loading: false, error: null, serverId: 'pep', gameDate: '200년 3월 중순', refresh: vi.fn(),
  generalId: mock.session.generalId,
  frontInfo: mock.session.generalId == null
    ? { general: { hasGeneral: false, generalId: null, name: null, nationId: 0 } }
    : { general: { hasGeneral: true, generalId: mock.session.generalId, name: '하후돈', nationId: mock.session.nationId } },
}) }));
vi.mock('@/lib/api', () => ({ api: { get: mock.get, mapPreview: mock.mapPreview } }));

const at = (month: number, phase: number, ordinal = 0) => ({ year: 200, month, phase, ordinal });
const e = (id: number, kind: string, section: string, when: ReturnType<typeof at>, refs: Record<string, number | string> = {},
  facts: Record<string, number | string> = {}): GameEvent => ({ id, kind, section, occurredAt: when, refs, facts });

const WORLD = [e(101, 'county.ownerChanged', 'WORLD', at(3, 2, 5), { CITY: 12, FROM_NATION: 2, TO_NATION: 1 })];
const COURT = [e(201, 'court.dispatchReceived', 'COURT', at(3, 2, 3), { REQUEST: 'req-1', ISSUER: 8, TARGET: 7 })];
const BATTLE = [e(301, 'deploy.started', 'BATTLE', at(2, 3))];
const PERSONAL = [e(401, 'yuedan.assessed', 'PERSONAL', at(3, 1), { ACTOR: 7 }, { RENOWN_BEFORE: 40, RENOWN_AFTER: 52, RENOWN_CHANGE: 12 }),
  e(402, 'mystery.kind', 'PERSONAL', at(3, 1, 1))];
const RETINUE = [e(501, 'enlist.retainerJoined', 'RETINUE_NATION', at(3, 1), { PERSON: 9 })];

/** 목록 안에서만 찾는다 — 데스크톱은 고른 줄의 문장이 오른쪽 칸에도 한 번 더 보인다. */
async function inList(text: string) {
  const list = await screen.findByRole('list', { name: '기록' });
  return within(list).findByText(text);
}

function page(events: readonly GameEvent[], nextCursor: string | null = null) {
  return Promise.resolve({ events, nextCursor });
}

function route(overrides: Record<string, () => Promise<unknown>> = {}) {
  mock.get.mockImplementation((path: string) => {
    for (const [key, respond] of Object.entries(overrides)) if (path.includes(key)) return respond();
    if (path.startsWith('/api/world-events')) return page(WORLD);
    if (path.includes('section=COURT')) return page(COURT);
    if (path.includes('section=BATTLE')) return page(BATTLE);
    if (path.includes('section=PERSONAL')) return page(PERSONAL);
    if (path.includes('section=RETINUE_NATION')) return page(RETINUE);
    return Promise.reject(new Error('500: unexpected'));
  });
}

describe('기록 5분류(P-H01)', () => {
  let viewport: ReturnType<typeof installViewport>;
  beforeEach(() => {
    vi.clearAllMocks();
    mock.session.generalId = 7;
    mock.session.nationId = 1;
    mock.mapPreview.mockResolvedValue({ cities: [{ id: 12, name: '허현' }], nations: [{ id: 1, name: '조조' }, { id: 2, name: '원소' }] });
    route();
    viewport = installViewport(1440);
  });
  afterEach(() => viewport.restore());

  it('전체는 다섯 분류를 시각순으로 합치고, 첫 줄이 오른쪽 「고른 기록」에 보인다(데스크톱)', async () => {
    render(<RecordsPage />);
    const list = await screen.findByRole('list', { name: '기록' });
    await waitFor(() => expect(within(list).getByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeInTheDocument());
    const texts = within(list).getAllByRole('button').map((b) => b.textContent);
    expect(texts[0]).toContain('허현의 소유 세력이');
    expect(texts[1]).toContain('어느 인물의 발령이 도착했습니다.'); // 인물 이름 사전 전 — 지어내지 않는다
    expect(within(list).getByText('200년 3월 중순')).toBeInTheDocument();
    expect(within(list).getByText('월단평에서 명망이 올랐습니다(40 → 52).')).toBeInTheDocument();
    expect(within(list).getByText('새 장수가 부에 들었습니다.')).toBeInTheDocument();
    expect(within(list).getByText('기록을 표시할 수 없습니다.')).toBeInTheDocument(); // 모르는 종류는 그 줄만
    expect(within(list).getByText('출병했습니다.')).toBeInTheDocument();
    expect(within(list).getByText('누구 · 어디 — 서버 준비 중')).toBeInTheDocument();
    expect(within(list).getByRole('link', { name: '응답하기' })).toBeInTheDocument();
    const detail = screen.getByRole('region', { name: /고른 기록/ });
    expect(within(detail).getByTestId('record-map')).toHaveTextContent('허현 지도');
    expect(within(detail).getByText('조조')).toBeInTheDocument();
  });

  it('분류를 고르면 그 분류만 읽는다 · 서버가 아직 쓰지 않는 종류를 알린다', async () => {
    const user = userEvent.setup();
    render(<RecordsPage />);
    await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
    await user.click(screen.getByRole('radio', { name: '전장 보고' }));
    expect(await inList('출병했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).not.toBeInTheDocument();
    expect(screen.getByText(/서버가 아직 사건으로 쓰지 않음: 개인 조우 · 조우 대기 · 조우 해산 · 보루 공성/)).toBeInTheDocument();
    expect(mock.get.mock.calls.filter(([path]) => String(path).includes('section=BATTLE'))).toHaveLength(1); // 이미 받은 것은 다시 받지 않는다
  });

  it('종류로 거르면 불러온 기록 안에서만 거른다고 알린다', async () => {
    const user = userEvent.setup();
    render(<RecordsPage />);
    await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
    await user.selectOptions(screen.getByRole('combobox', { name: '종류' }), '내 월단평');
    const list = screen.getByRole('list', { name: '기록' });
    expect(within(list).getAllByRole('button')).toHaveLength(1);
    expect(screen.getByText(/불러온 기록 안에서만 거릅니다/)).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '현 점령' })).not.toBeInTheDocument(); // 옛 별칭은 고를 거리가 아니다
  });

  it('더 보기는 before 커서로 다음 쪽을 붙인다', async () => {
    const user = userEvent.setup();
    route({ 'section=COURT&before=c2': () => page([e(202, 'court.dispatchCancelled', 'COURT', at(1, 1), { REQUEST: 'r', ISSUER: 8 })]),
      'section=COURT': () => page(COURT, 'c2') });
    render(<RecordsPage />);
    await user.click(await screen.findByRole('radio', { name: '조정 공문' }));
    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    expect(await inList('어느 인물의 발령이 더는 유효하지 않아 불이익 없이 취소됐습니다.')).toBeInTheDocument();
    expect(mock.get).toHaveBeenCalledWith('/api/events?section=COURT&before=c2&limit=50', expect.anything());
  });

  it('장수가 없으면 비공개 분류는 부르지 않고, 전체는 천하 정세만 보인다', async () => {
    const user = userEvent.setup();
    mock.session.generalId = null;
    render(<RecordsPage />);
    expect(await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeInTheDocument();
    expect(screen.getByText('이 서버에 내 장수가 없어 천하 정세만 보입니다.')).toBeInTheDocument();
    expect(mock.get.mock.calls.every(([path]) => String(path).startsWith('/api/world-events'))).toBe(true);
    await user.click(screen.getByRole('radio', { name: '개인 행적' }));
    expect(await screen.findByText('이 서버에 내 장수가 없습니다')).toBeInTheDocument();
  });

  it('재야의 부 · 세력 빈 상태', async () => {
    const user = userEvent.setup();
    mock.session.nationId = 0;
    route({ 'section=RETINUE_NATION': () => page([]) });
    render(<RecordsPage />);
    await user.click(await screen.findByRole('radio', { name: '부 · 세력' }));
    expect(await screen.findByText('소속 세력이 없어 세력 소식이 없습니다')).toBeInTheDocument();
  });

  it('불러오지 못하면 다시 시도할 수 있다', async () => {
    const user = userEvent.setup();
    let fail = true;
    route({ 'world-events': () => (fail ? Promise.reject(new Error('503: Service Unavailable')) : page(WORLD)) });
    render(<RecordsPage />);
    await user.click(await screen.findByRole('radio', { name: '천하 정세' }));
    expect(await screen.findByText('기록을 불러오지 못했습니다')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: /다시 시도/ }));
    expect(await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeInTheDocument();
  });

  it('전체에서 한 분류만 실패하면 받은 것만 합치고, 그 분류만 다시 부른다', async () => {
    const user = userEvent.setup();
    let fail = true;
    route({ 'section=COURT': () => (fail ? Promise.reject(new Error('503: Service Unavailable')) : page(COURT)) });
    render(<RecordsPage />);
    expect(await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('불러오지 못한 분류(조정 공문)는 빼고 합쳤습니다.');
    fail = false;
    const before = mock.get.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await inList('어느 인물의 발령이 도착했습니다.')).toBeInTheDocument();
    expect(mock.get.mock.calls.slice(before).map(([path]) => path)).toEqual(['/api/events?section=COURT&limit=50']);
  });

  it('새 순이 끝나면 「새 기록 n건」만 알리고, 누르면 붙인다', async () => {
    const user = userEvent.setup();
    render(<RecordsPage />);
    await user.click(await screen.findByRole('radio', { name: '천하 정세' }));
    await inList('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
    route({ 'world-events': () => page([e(102, 'yuedan.announced', 'WORLD', at(4, 1)), ...WORLD]) });
    act(() => deliverTurnCompleted());
    await user.click(await screen.findByRole('button', { name: '새 기록 1건 — 맨 위로' }));
    expect(await inList('200년 4월 월단평 결과가 발표됐습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /새 기록/ })).not.toBeInTheDocument();
  });

  it('모바일은 줄을 누르면 하단 시트, Escape 로 닫고 그 줄로 초점을 돌린다', async () => {
    viewport.restore();
    viewport = installViewport(390);
    const user = userEvent.setup();
    render(<RecordsPage />);
    const row = await screen.findByRole('button', { name: /허현의 소유 세력이/ });
    expect(screen.queryByRole('region', { name: /고른 기록/ })).not.toBeInTheDocument();
    await user.click(row);
    const sheet = await screen.findByRole('dialog', { name: '천하 정세 · 200년 3월 중순' });
    expect(within(sheet).getByTestId('record-map')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(row).toHaveFocus();
  });
});
