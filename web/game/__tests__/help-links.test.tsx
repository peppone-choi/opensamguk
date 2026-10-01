import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { ReasonTooltip, StatusView } from '@opensamguk/ui';
import { __resetHelpCache } from '../lib/help';
import { helpHref } from '../lib/help-route';

// 게임 화면이 도움말 서랍을 여는 길(useOpenHelp · useReasonHelp().onHelp) — 실제 공용 사유 시트 · 「거부됨」 상태를 그려 끝까지 누른다.
// 맥락 없는 공용 부품의 기본 링크(`?help=…`)는 다른 쿼리를 지운다. /game 은 레이아웃의 HelpLinkScope 가 맥락을 준다(HelpLinkScope.test).
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const nav = vi.hoisted(() => ({ pathname: '/game/pep/court', search: 'tab=orders' }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));

import { useReasonHelp } from '../hooks/useHelp';
import { useOpenHelp } from '../hooks/useOpenHelp';

const FIX = resolve(__dirname, '../../../docs/development/fixtures/help-tutorial');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(FIX, `${name}.json`), 'utf-8'));
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
    __resetHelpCache();
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (
        url === '/api/game/api/help/failures/ALREADY_SERVING?inputId=action.enlist'
            ? json(200, fixture('help-failure-already-serving'))
            : json(404, { error: { code: 'FAILURE_REASON_NOT_FOUND', message: '' } }))));
});
afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    nav.pathname = '/game/pep/court';
    nav.search = 'tab=orders';
});

test('helpHref keeps the path and every other query, and sets (or replaces) only help', () => {
    expect(helpHref('/game/pep/court', 'tab=orders', 'input:court.dispatch!NOT_RULER'))
        .toBe('/game/pep/court?tab=orders&help=input%3Acourt.dispatch%21NOT_RULER');
    expect(helpHref('/game/pep/corps/siege', new URLSearchParams('county=7&help=home'), { kind: 'failure', reason: 'NOT_RULER', inputId: 'court.dispatch' }))
        .toBe('/game/pep/corps/siege?county=7&help=failure%3ANOT_RULER%40court.dispatch');
    expect(helpHref('/game/pep', null, { kind: 'search', q: '출사 비용' })).toBe('/game/pep?help=search%3A%EC%B6%9C%EC%82%AC+%EB%B9%84%EC%9A%A9');
});

function ReasonScreen({ withOnHelp }: { withOnHelp: boolean }) {
    const help = useReasonHelp('ALREADY_SERVING', 'action.enlist');
    const { onHelp, ...rest } = help;
    return (
        <ReasonTooltip reason="이미 다른 주공을 섬기고 있습니다." {...rest} onHelp={withOnHelp ? onHelp : undefined} defaultOpen>
            <button type="button" aria-disabled="true">출사</button>
        </ReasonTooltip>
    );
}

test('a reason sheet spread from useReasonHelp opens the drawer on this page, keeping ?tab=orders, without reloading', async () => {
    render(<ReasonScreen withOnHelp />);
    await waitFor(() => expect(screen.getByText('현재 소속 관계를 확인한 뒤 출사 가능한 상태에서 다시 시도하세요.')).toBeInTheDocument());
    const link = screen.getByRole('link', { name: '도움말 — 출사 →' });
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    fireEvent(link, click);
    expect(click.defaultPrevented).toBe(true); // 문서 이동(옛 쿼리를 지우는 기본 링크) 대신
    expect(router.push).toHaveBeenCalledWith('/game/pep/court?tab=orders&help=input%3Aaction.enlist%21ALREADY_SERVING', { scroll: false });
});

test('outside a HelpLinkScope and without onHelp the shared part falls back to ?help=… — the other query is lost (/game supplies the scope)', async () => {
    render(<ReasonScreen withOnHelp={false} />);
    const link = await screen.findByRole('link', { name: '도움말 — 출사 →' });
    expect(link).toHaveAttribute('href', '?help=input%3Aaction.enlist!ALREADY_SERVING'); // encodeURIComponent 는 ! 를 그대로 둔다(같은 값으로 읽힌다)
    expect(link.getAttribute('href')).not.toContain('tab=orders');
});

function DeniedScreen() {
    const openHelp = useOpenHelp();
    return (
        <StatusView kind="denied" title="발령은 주공만 할 수 있습니다" howTo="주공이 되려면 거병하거나 독립해야 합니다."
            helpTopic={{ id: 'input:court.dispatch!NOT_RULER', title: '발령' }} onHelp={openHelp} />
    );
}

test('a denied status with a help topic opens the drawer through useOpenHelp', () => {
    nav.pathname = '/game/pep/court';
    nav.search = 'tab=orders&person=3';
    render(<DeniedScreen />);
    fireEvent.click(screen.getByRole('link', { name: '도움말 — 발령' }));
    expect(router.push).toHaveBeenCalledWith('/game/pep/court?tab=orders&person=3&help=input%3Acourt.dispatch%21NOT_RULER', { scroll: false });
});
