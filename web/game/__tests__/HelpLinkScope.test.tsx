import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReasonSheet, StatusView } from '@opensamguk/ui';

const nav = vi.hoisted(() => ({ pathname: '/game/pep/court', search: 'tab=orders' }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));

import HelpLinkScope from '../components/shell/HelpLinkScope';

const TOPIC = { id: 'input:court.dispatch!NOT_RULER', title: '발령' };

describe('HelpLinkScope — 공용 부품의 도움말 링크가 지금 쿼리를 지킨다(K0 2026-10-01)', () => {
    beforeEach(() => {
        router.push.mockClear();
        nav.pathname = '/game/pep/court';
        nav.search = 'tab=orders';
    });

    it('onHelp 없이 helpTopic 만 넘겨도 href 에 ?tab=orders 가 남고, 보통 누르기는 router.push(scroll: false)', () => {
        render(
            <HelpLinkScope>
                <ReasonSheet reason="주공만 할 수 있습니다" title="발령은 주공만" helpTopic={TOPIC} defaultOpen>
                    <button type="button" aria-disabled="true">발령</button>
                </ReasonSheet>
                <StatusView kind="denied" title="볼 수 없습니다" howTo="주공이 되면 볼 수 있습니다" helpTopic={{ id: 'court', title: '조정' }} />
            </HelpLinkScope>,
        );
        const reason = screen.getByRole('link', { name: /도움말 — 발령/ });
        const denied = screen.getByRole('link', { name: /도움말 — 조정/ });
        expect(reason).toHaveAttribute('href', `/game/pep/court?${new URLSearchParams({ tab: 'orders', help: TOPIC.id })}`);
        expect(denied).toHaveAttribute('href', '/game/pep/court?tab=orders&help=court');
        fireEvent.click(denied);
        expect(router.push).toHaveBeenCalledWith('/game/pep/court?tab=orders&help=court', { scroll: false });
    });

    it('이미 열린 도움말은 바꿔 넣는다(help 가 둘이 되지 않는다) · 다른 쿼리는 그대로', () => {
        nav.search = 'county=7&help=home';
        render(
            <HelpLinkScope>
                <StatusView kind="denied" title="볼 수 없습니다" howTo="—" helpTopic={{ id: 'county', title: '현' }} />
            </HelpLinkScope>,
        );
        expect(screen.getByRole('link', { name: /도움말 — 현/ })).toHaveAttribute('href', '/game/pep/court?county=7&help=county');
    });
});
