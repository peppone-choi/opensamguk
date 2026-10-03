// 군단(P-C01) — 서버 값만(군 이름 · 병력 · 구간 · 시야 · 방침) · 출병/부대 모으기는 명령 흐름으로 · 편성 해제는 후보일 때만 ·
// 세력 작전 · 전투 잠김은 서버 대기 · 편성 해제 사유 시트에 도움말(K7 useReasonHelp).
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CorpsPanel } from '../components/corps/CorpsPanel';
import { __resetHelpCache } from '../lib/help';
import { deployOrderOf, releaseChoiceFor, toCorpsRows } from '../lib/corps/corps-model';
import type { CorpsList, Policies, Visibility } from '../lib/campaign-reads';

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/corps',
    useSearchParams: () => new URLSearchParams(''),
    useRouter: () => router,
}));
/** 도움말 실패 사유 응답 — 없으면 원장에 없는 사유(404). */
const failures = new Map<string, unknown>();
beforeEach(() => {
    __resetHelpCache();
    failures.clear();
    const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        const m = /\/api\/game\/api\/help\/failures\/([^?]+)\?inputId=(.+)$/.exec(url);
        const hit = m ? failures.get(`${decodeURIComponent(m[1])}@${decodeURIComponent(m[2])}`) : undefined;
        return hit ? respond(200, hit) : respond(404, { error: { code: 'FAILURE_REASON_NOT_FOUND', message: '' } });
    }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

const MINE = { corpsId: 'C-1', ownerGeneralId: 1, commanderGeneralId: 1, commanderName: '[나]', nationId: 3, nationColor: '#aa3333', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL' as never, own: true, troops: 1200, marchPath: ['P-1', 'P-2'], destinationProvinceId: 'P-2' };
const corps: CorpsList = {
    status: 'READY',
    corps: [
        MINE,
        { corpsId: 'C-9', ownerGeneralId: 9, commanderGeneralId: 9, commanderName: '[적장]', nationId: 5, nationColor: '#3333aa', provinceId: 'P-7', commanderyNo: 40, visibility: 'INTEL' as never, own: false, troopsBand: { code: 'B3', label: '3천 안팎' }, ageTurns: 2 },
    ],
};
const vision: Visibility = { status: 'READY', commanderies: [{ no: 12, id: 'c12', name: '영천군', tier: 'FULL' as never }] };
const policies: Policies = {
    status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' }, counties: [],
    corps: [{ orderId: 'C-1', commanderName: '[나]', active: null, pending: { policy: 'EVADE', label: '회피' }, settable: true, blocked: null }],
} as never;
const deploy = { available: true, maxReservedTurns: 12 as const, bugoks: [], destinations: [{ provinceId: 'P-2', name: '진류' }], order: { orderId: 'O-1', destinationProvinceId: 'P-2', stop: 'ENCOUNTER' } };

// 주인(1)이 직접 이끄는 군단 C-1과 부장(10)이 이끄는 군단 C-2. 서버 선택지 순서는 DeploymentState.corps 순서(주인 군단 먼저).
const twoCorps: CorpsList = {
    status: 'READY',
    corps: [
        MINE,
        { corpsId: 'C-2', ownerGeneralId: 1, commanderGeneralId: 10, commanderName: '[부장]', nationId: 3, nationColor: '#aa3333', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL' as never, own: true, troops: 600, marchPath: [] },
    ],
};
const twoChoices = {
    inputId: 'court.releaseCorps' as const, available: true,
    choices: [
        { label: '[나] 군단', arguments: { targetGeneralId: 1 }, available: true },
        { label: '[부장] 군단', arguments: { targetGeneralId: 10 }, available: true },
    ],
};

describe('군단 모델', () => {
    it('내 군단은 병력 · 목적지 이름, 남의 군단은 구간 · 시야 — 모르는 곳은 null', () => {
        const [mine, other] = toCorpsRows(corps, vision, deploy);
        expect(mine).toMatchObject({ own: true, where: '영천군', troops: 1200, band: null, marching: true, destination: '진류' });
        expect(other).toMatchObject({ own: false, where: null, troops: null, band: '3천 안팎', ageTurns: 2 });
        expect(deployOrderOf(deploy)).toEqual({ destination: '진류', stop: '조우 중단' });
        expect(toCorpsRows({ status: 'WRONG_RULE_PROFILE' }, vision, deploy)).toEqual([]);
    });

    it('군단 방침은 corpsId(= 출병 orderId)로 잇고, 방침이 없으면 null — 현 기본 방침(수비)을 붙이지 않는다', () => {
        const [mine, other] = toCorpsRows(corps, vision, deploy, policies);
        expect(mine).toMatchObject({ policy: null, pendingPolicy: '회피', policyKnown: true });
        expect(other).toMatchObject({ policy: null, pendingPolicy: null, policyKnown: false });
        const active = { ...policies, corps: [{ ...policies.corps[0], active: { policy: 'INTERCEPT', label: '요격' }, pending: null }] } as Policies;
        expect(toCorpsRows(corps, vision, deploy, active)[0]).toMatchObject({ policy: '요격', pendingPolicy: null });
        expect(toCorpsRows(corps, vision, deploy, { ...policies, status: 'NOT_READY' } as Policies)[0]).toMatchObject({ policyKnown: false });
    });

    // #1192 리뷰: 서버(CourtActionOptionsService · CourtRules)는 군단을 군단장으로만 고른다. 주인이 직접 이끄는 군단과 부장
    // 군단을 함께 내면 두 군단의 주인이 같다 — 주인으로 대체 매칭하면 부장 군단 카드가 주인 군단의 선택지를 잡는다.
    it('편성 해제 선택지는 군단장으로만 잇는다 — 주인이 같은 두 군단에서 부장 군단은 부장 선택지', () => {
        const [mine, deputy] = toCorpsRows(twoCorps, vision, deploy);
        expect(releaseChoiceFor(twoChoices, deputy)?.arguments).toEqual({ targetGeneralId: 10 });
        expect(releaseChoiceFor(twoChoices, mine)?.arguments).toEqual({ targetGeneralId: 1 });
        const onlyMine = { ...twoChoices, choices: [twoChoices.choices[0]] };
        expect(releaseChoiceFor(onlyMine, deputy)).toBeNull();
    });
});

describe('군단 칸', () => {
    const rows = toCorpsRows(corps, vision, deploy, policies);
    const base = {
        load: { state: 'ready' as const, rows }, order: deployOrderOf(deploy),
        releaseOptions: { inputId: 'court.releaseCorps' as const, available: true, choices: [{ label: '[나] 군단', arguments: { targetGeneralId: 1 }, available: true }] },
        onOpenFlow: vi.fn(), onRelease: vi.fn(async () => ({ ok: true })),
    };

    it('출병 · 부대 모으기는 명령 흐름을 연다', () => {
        const onOpenFlow = vi.fn();
        render(<CorpsPanel {...base} onOpenFlow={onOpenFlow} />);
        fireEvent.click(screen.getByRole('button', { name: '출병' }));
        fireEvent.click(screen.getByRole('button', { name: '부대 모으기' }));
        expect(onOpenFlow.mock.calls).toEqual([['action.deploy'], ['action.muster']]);
        expect(screen.getByRole('status')).toHaveTextContent('지금 출병 명령 · 목적지 진류 · 조우 중단');
    });

    it('편성 해제는 확인 뒤 서버가 준 인자로 보내고, 거절되면 그 사유로 막는다', async () => {
        const onRelease = vi.fn(async () => ({ ok: false, code: 'NOT_RULER', reason: '주공만 할 수 있습니다' }));
        render(<CorpsPanel {...base} onRelease={onRelease} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        const card = screen.getByRole('article', { name: '군단 — [나]' });
        expect(within(card).getByText('영천군')).toBeInTheDocument();
        fireEvent.click(within(card).getByRole('button', { name: '편성 해제' }));
        expect(screen.getByText('군단 편성을 풉니다')).toBeInTheDocument();
        await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: '편성 해제' }).at(-1)!); });
        expect(onRelease).toHaveBeenCalledWith(rows[0], { targetGeneralId: 1 });
        const blocked = within(screen.getByRole('article', { name: '군단 — [나]' })).getByRole('button', { name: '편성 해제' });
        expect(blocked).toHaveAttribute('data-input-status', 'BLOCKED');
        expect(screen.getAllByText('주공만 할 수 있습니다').length).toBeGreaterThan(0);
    });

    it('주인이 같은 두 군단에서 부장 군단 카드의 「편성 해제」는 부장 군단을 푼다(#1192 리뷰)', async () => {
        const two = toCorpsRows(twoCorps, vision, deploy, policies);
        const onRelease = vi.fn(async () => ({ ok: true }));
        render(<CorpsPanel {...base} load={{ state: 'ready', rows: two }} releaseOptions={twoChoices} onRelease={onRelease} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button', { name: /\[부장\]/ }));
        const card = screen.getByRole('article', { name: '군단 — [부장]' });
        fireEvent.click(within(card).getByRole('button', { name: '편성 해제' }));
        await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: '편성 해제' }).at(-1)!); });
        expect(onRelease).toHaveBeenCalledWith(two[1], { targetGeneralId: 10 });
    });

    it('내 군단 카드 — 방침(없으면 「방침 없음」 · 다음 순부터) · 전투 잠김은 서버 대기 · 「군단장 바꾸기」는 배치 · 방침 화면', () => {
        const onOpenPolicy = vi.fn();
        render(<CorpsPanel {...base} onOpenPolicy={onOpenPolicy} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        const card = screen.getByRole('article', { name: '군단 — [나]' });
        expect(card).toHaveTextContent('방침방침 없음 · 다음 순부터 회피');
        expect(card).not.toHaveTextContent('수비');
        expect(card.querySelector('[data-contract="K6-11"]')).toHaveTextContent('서버 대기');
        fireEvent.click(within(card).getByRole('button', { name: '군단장 바꾸기' }));
        expect(onOpenPolicy).toHaveBeenCalledTimes(1);
    });

    it('편성 해제를 서버가 받지 않으면 사유 시트에 「이렇게 하면 됩니다」와 도움말(지금 주소를 두고 서랍)', async () => {
        failures.set('NOT_RULER@court.releaseCorps', {
            schemaVersion: 1, reason: 'NOT_RULER', reviewState: 'DRAFT', explanation: '주공만 할 수 있습니다',
            recoveryAdvice: '주공에게 편성 해제를 부탁하세요.', relatedTopicIds: [],
        });
        const onRelease = vi.fn(async () => ({ ok: false, code: 'NOT_RULER', reason: '주공만 할 수 있습니다' }));
        render(<CorpsPanel {...base} onRelease={onRelease} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        fireEvent.click(within(screen.getByRole('article', { name: '군단 — [나]' })).getByRole('button', { name: '편성 해제' }));
        await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: '편성 해제' }).at(-1)!); });
        expect(await screen.findByText('주공에게 편성 해제를 부탁하세요.')).toBeInTheDocument();
        const link = screen.getByRole('link', { name: /^도움말 — / });
        const click = new MouseEvent('click', { bubbles: true, cancelable: true });
        fireEvent(link, click);
        expect(click.defaultPrevented).toBe(true);
        expect(router.push).toHaveBeenCalledWith('/game/pep/corps?help=input%3Acourt.releaseCorps%21NOT_RULER', { scroll: false });
    });

    it('남의 군단은 편성 해제가 없고 시야를 「n순 전 첩보」로 보인다', () => {
        render(<CorpsPanel {...base} />);
        const other = within(screen.getByRole('region', { name: '보이는 다른 군단' })).getByRole('button');
        expect(other).toHaveTextContent('2순 전 첩보');
        fireEvent.click(other);
        expect(within(screen.getByRole('article', { name: '군단 — [적장]' })).queryByRole('button', { name: '편성 해제' })).toBeNull();
    });

    it('출전한 군단이 없음 · 실패 · 세력 작전 서버 대기', () => {
        const retry = vi.fn();
        const { rerender } = render(<CorpsPanel {...base} load={{ state: 'ready', rows: [] }} />);
        expect(screen.getByText('출전한 군단이 없습니다')).toBeInTheDocument();
        rerender(<CorpsPanel {...base} load={{ state: 'error', onRetry: retry }} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(retry).toHaveBeenCalled();
        fireEvent.click(screen.getByRole('tab', { name: '세력 작전' }));
        expect(screen.getByText('세력 작전 준비 중')).toBeInTheDocument();
    });
});
