import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, configure, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { __resetHelpCache } from '../lib/help';

// 셸 도움말 서랍(components/shell/HelpDrawer) — `?help=` 보기 하나를 K7 본문으로 그리고, 서랍 안 이동은 같은 쿼리만 바꾼다.
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const nav = vi.hoisted(() => ({ pathname: '/game/pep/retinue', search: 'person=3&help=home' }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));

import HelpDrawer from '../components/shell/HelpDrawer';

const FIX = resolve(__dirname, '../../../docs/development/fixtures/help-tutorial');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(FIX, `${name}.json`), 'utf-8'));
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

let viewport: ReturnType<typeof installViewport> | null = null;

beforeEach(() => {
    __resetHelpCache();
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        if (url === '/api/game/api/help/context?inputId=action.enlist') return json(200, fixture('help-context-action-enlist'));
        return json(404, { error: { code: 'INPUT_NOT_FOUND', message: '' } });
    }));
});
afterEach(() => {
    viewport?.restore();
    viewport = null;
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    nav.pathname = '/game/pep/retinue';
    nav.search = 'person=3&help=home';
});

async function drawer(view: string, groupKey: string | null = 'retinue', screenPath: string | null = 'retinue') {
    render(<HelpDrawer view={view} closeHref="?person=3" groupKey={groupKey} screenPath={screenPath} />);
    const box = screen.getByRole('complementary', { name: '도움말' });
    // 본문은 서랍을 열 때 받는다(lazy) — 받기 전에는 불러오는 중.
    await within(box).findByRole('heading', { name: '도움말', level: 2 });
    await act(async () => { await Promise.resolve(); });
    return box;
}

test('홈은 지금 화면(부)의 일 — 줄을 누르면 서랍 쿼리만 바꿔 연다(다른 쿼리는 둔다)', async () => {
    const box = await drawer('home');
    expect(within(box).getByText('부에서 하는 일')).toBeInTheDocument();
    expect(within(box).queryByRole('button', { name: '앞 보기로' })).toBeNull();
    fireEvent.click(within(box).getByRole('button', { name: /^인재탐색/ }));
    expect(router.push).toHaveBeenLastCalledWith('/game/pep/retinue?person=3&help=input%3Aaction.search', { scroll: false });
});

test('주제 보기 — 뒤로는 주소 내역, 닫기 · Esc 는 help 쿼리를 뺀 주소', async () => {
    nav.search = 'person=3&help=input%3Aaction.enlist';
    const box = await drawer('input:action.enlist');
    expect(await within(box).findByRole('heading', { name: '출사' })).toBeInTheDocument();
    fireEvent.click(within(box).getByRole('button', { name: '앞 보기로' }));
    expect(router.back).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(within(box).getByRole('heading', { name: '출사' }), { key: 'Escape' });
    expect(router.push).toHaveBeenLastCalledWith('/game/pep/retinue?person=3', { scroll: false });
    fireEvent.click(within(box).getByRole('button', { name: '도움말 닫기(Esc)' }));
    expect(router.push).toHaveBeenCalledTimes(2);
});

test('찾기어는 첫 글자에 push, 이어 쓰면 replace — 주소 내역을 쌓지 않는다', async () => {
    const box = await drawer('home');
    fireEvent.change(within(box).getByRole('searchbox'), { target: { value: '출사' } });
    expect(router.push).toHaveBeenLastCalledWith('/game/pep/retinue?person=3&help=search%3A%EC%B6%9C%EC%82%AC', { scroll: false });
});

test('이미 찾는 중이면 replace', async () => {
    nav.search = 'help=search%3A%EC%B6%9C%EC%82%AC';
    const box = await drawer('search:출사');
    fireEvent.change(within(box).getByRole('searchbox'), { target: { value: '출사하' } });
    expect(router.replace).toHaveBeenLastCalledWith('/game/pep/retinue?help=search%3A%EC%B6%9C%EC%82%AC%ED%95%98', { scroll: false });
    expect(router.push).not.toHaveBeenCalled();
});

test('잘못된 보기 값은 홈으로 — 셸 밖 화면(묶음 없음)은 「이 화면에서 따로 하는 일은 없습니다」', async () => {
    const box = await drawer('input:../../x', null, null);
    expect(within(box).getByText('이 화면에서 따로 하는 일은 없습니다')).toBeInTheDocument();
});

test('데스크톱은 열리면 찾기칸에 포커스, 모바일은 하지 않는다(자판이 서랍을 덮는다)', async () => {
    viewport = installViewport(1440);
    let box = await drawer('home');
    expect(within(box).getByRole('searchbox')).toHaveFocus();
    expect(box.querySelector('[data-help-panel]')).toHaveAttribute('data-help-panel', 'drawer');
    viewport.restore();
    document.body.innerHTML = '';
    viewport = installViewport(390);
    box = await drawer('home');
    expect(within(box).getByRole('searchbox')).not.toHaveFocus();
    expect(box.querySelector('[data-help-panel]')).toHaveAttribute('data-help-panel', 'sheet');
});
