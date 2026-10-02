import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({
    default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock('@/components/admin/MemberControl', () => ({ default: () => <div>members</div> }));

import AdminPage from '@/app/admin/page';

// P-G09 서버 탭(설계서 §3.4 S1–S83, 보드 V31K5AdminServer) — 쓰기는 모두 흉내 응답에만 댄다.
const svc = (tag: string) => ({ reachable: true, version: '0.9.2', imageTag: tag, buildTime: null });
const VERSION = {
    gateway: svc('sha-1'),
    skew: true,
    servers: [{ id: 'pep', name: 'pep', generation: 1, scenarioCode: 'scenario_1020', gameApi: svc('sha-1'), gameEngine: svc('sha-0'), skew: true }],
};
const DEPLOY = { configured: true, serverId: 'pep', currentTag: 'sha-1', availableTags: ['sha-2', 'sha-1'], latestTag: 'sha-2', promotionAvailable: true };
const SETTINGS = {
    msg: '공지',
    logWritable: true,
    scenarioCode: 'scenario_1020',
    scenarioText: '군웅할거',
    mapCode: 'han',
    year: 200,
    month: 3,
    turnPhaseText: '중순',
    status: '열림',
    startyear: 190,
    turnterm: 10,
    turnOptions: [5, 10],
    blockedWrites: [],
    editableFields: [
        { key: 'msg', label: '운영자 메세지', type: 'text', value: '공지' },
        { key: 'npcmode', label: 'NPC 빙의', type: 'select', value: 0, options: [{ value: '0', label: '불가' }] },
        { key: 'block_general_create', label: '장수 임의 생성', type: 'select', value: 0, options: [{ value: '0', label: '가능' }] },
        { key: 'maxgeneral', label: '최대 장수', type: 'number', value: 30 },
        { key: 'maxnation', label: '최대 국가', type: 'number', value: 55 },
        { key: 'startyear', label: '시작 년도', type: 'number', value: 190 },
        { key: 'starttime', label: '시작 시간', type: 'text', value: '2026-09-30 20:00' },
        { key: 'turnterm', label: '턴 시간(분)', type: 'select', value: 10, options: [{ value: '5', label: '5분' }, { value: '10', label: '10분' }] },
    ],
};
const field = (key: string, value: string | null, extra: Record<string, unknown> = {}) => ({ key, value, configured: true, writeOnly: false, masked: false, ...extra });
const SHARED_ENV = {
    scope: 'shared',
    configured: true,
    fields: {
        ADMIN_PASSWORD: field('ADMIN_PASSWORD', null, { writeOnly: true, masked: true }),
        COOKIE_SECURE: field('COOKIE_SECURE', 'true'),
    },
};
const SERVER_ENV = {
    scope: 'server',
    configured: true,
    fields: {
        IMAGE_TAG: field('IMAGE_TAG', 'sha-1'),
        RESET_TURNTERM: field('RESET_TURNTERM', '60'),
        RESET_SYNC: field('RESET_SYNC', 'Y'),
        RESET_NPCMODE: field('RESET_NPCMODE', '0'),
    },
};

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type Route = (path: string, init?: RequestInit) => Response | undefined;

function stubFetch(route: Route = () => undefined) {
    const fake = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        const hit = route(path, init);
        if (hit) return Promise.resolve(hit);
        if (path === '/api/proxy/admin/version') return Promise.resolve(json(VERSION));
        if (path === '/api/proxy/admin/scenarios') return Promise.resolve(json({ scenarios: [{ code: 'scenario_1020', title: '군웅할거' }] }));
        if (path === '/api/proxy/admin/deploy/status?serverId=pep') return Promise.resolve(json(DEPLOY));
        if (path === '/api/game/api/admin/game-settings?server=pep') return Promise.resolve(json(SETTINGS));
        if (path === '/api/proxy/admin/env/shared') return Promise.resolve(json(SHARED_ENV));
        if (path === '/api/proxy/admin/env/servers/pep') return Promise.resolve(json(SERVER_ENV));
        return Promise.resolve(new Response(null, { status: 404 }));
    });
    vi.stubGlobal('fetch', fake);
    return fake;
}

function calls(fake: ReturnType<typeof stubFetch>, method: string) {
    return fake.mock.calls
        .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
        .map(([path, init]) => ({ path: String(path), body: JSON.parse(String((init as RequestInit).body)) as unknown }));
}

async function openServerTab() {
    render(<AdminPage />);
    fireEvent.click(within(screen.getByRole('navigation', { name: '운영 콘솔' })).getByRole('button', { name: '서버' }));
    await screen.findByRole('heading', { name: '실행 버전' });
}

describe('P-G09 운영 콘솔 · 서버 탭', () => {
    beforeEach(() => {
        vi.stubGlobal('React', React);
    });
    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('버전 불일치는 기호 대신 경고 아이콘, 지금 버전이면 배포 단추에 사유', async () => {
        stubFetch();
        const { container } = render(<AdminPage />);
        fireEvent.click(within(screen.getByRole('navigation', { name: '운영 콘솔' })).getByRole('button', { name: '서버' }));
        const warn = await screen.findByText(/버전 불일치 — game-engine은 자동 재배포 제외/);
        expect(warn.textContent).not.toContain('⚠');
        expect(warn.querySelector('svg[data-icon="alert"]')).not.toBeNull();
        expect(container.textContent).not.toContain('⚠');

        const deploy = await screen.findByRole('button', { name: '이 버전으로 배포' });
        expect(deploy).toHaveAttribute('aria-disabled', 'true');
        expect(deploy).toHaveAttribute('data-reason', '지금 버전입니다');
        fireEvent.change(screen.getByRole('combobox', { name: 'pep 배포 태그 선택' }), { target: { value: 'sha-2' } });
        expect(screen.getByRole('button', { name: '최신 버전 배포' })).not.toHaveAttribute('aria-disabled');
    });

    it('배포 상태 조회가 실패하면 「조회 중」에 머물지 않고 오류 줄 + 다시 시도', async () => {
        let fail = true;
        stubFetch((path) => (path === '/api/proxy/admin/deploy/status?serverId=pep' && fail ? new Response(null, { status: 502 }) : undefined));
        await openServerTab();
        const panel = screen.getByRole('region', { name: '버전 배포 · 리셋 · 삭제' });
        await within(panel).findByText('배포 상태를 불러오지 못했습니다');
        expect(screen.queryByText(/상태 조회 중/)).toBeNull();
        fail = false;
        fireEvent.click(within(panel).getByRole('button', { name: '다시 시도' }));
        expect(await within(panel).findByText('지금 sha-1')).toBeInTheDocument();
    });

    it('새 서버는 이름이 빈 칸에서 시작하고, 확인 창에서 넣은 값을 보여 준 뒤에만 보낸다', async () => {
        const fake = stubFetch();
        await openServerTab();
        expect(screen.getByRole('textbox', { name: '서버 이름' })).toHaveValue('');
        // 시나리오 자동 시드는 보드 · 옛 화면 그대로 체크 상자다.
        expect(screen.getByRole('checkbox', { name: '시나리오 자동 시드' })).toBeChecked();
        expect(screen.queryByDisplayValue('통일 서버')).toBeNull();
        fireEvent.change(screen.getByRole('textbox', { name: '서버 이름' }), { target: { value: '새 서버' } });
        fireEvent.click(screen.getByRole('button', { name: '서버 생성' }));
        const dialog = screen.getByRole('dialog', { name: '서버 생성 확인' });
        expect(dialog).toHaveTextContent('새 서버');
        expect(dialog).toHaveTextContent('군웅할거 (scenario_1020)');
        fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
        expect(calls(fake, 'POST')).toEqual([]);
    });

    it('게임 설정: 뺀 칸은 없고 이름을 바꿨다, 저장은 바뀐 칸을 보여 준 뒤 고른 서버에만', async () => {
        const fake = stubFetch((path, init) => (init?.method === 'PATCH' && path.startsWith('/api/game/') ? json({ result: true, restartRequired: false }) : undefined));
        await openServerTab();
        const panel = await screen.findByRole('region', { name: '게임 설정' });
        expect(within(panel).getByRole('heading', { name: '게임 설정 · pep 1기' })).toBeInTheDocument();
        await within(panel).findByText('사람 장수 상한');
        for (const gone of ['운영자 메세지', 'NPC 빙의', '장수 임의 생성', '최대 국가', '최대 장수', '시작 년도', '시작 시간', '턴 시간(분)', '입장 설정']) {
            expect(within(panel).queryByText(gone)).toBeNull();
        }
        expect(within(panel).getByText('시작 시각')).toBeInTheDocument();
        expect(within(panel).getByText('형식 2026-10-03 20:00')).toBeInTheDocument();
        expect(within(panel).getByText('엔진을 다시 띄워야 적용됩니다')).toBeInTheDocument();
        // 시작 연도는 요약 칸에 읽기만(S68), 한 순은 「턴 n분」 대신 「n분」(S57–S62).
        expect(within(panel).getByText('시작 연도')).toBeInTheDocument();
        expect(within(panel).getByText('190')).toBeInTheDocument();
        expect(within(panel).getByText('10분', { selector: 'dd' })).toBeInTheDocument();

        const save = within(panel).getByRole('button', { name: '저장' });
        expect(save).toHaveAttribute('data-reason', '바꾼 값이 없습니다');
        fireEvent.change(within(panel).getByRole('spinbutton', { name: '사람 장수 상한' }), { target: { value: '24' } });
        fireEvent.click(within(panel).getByRole('button', { name: '저장' }));
        const dialog = screen.getByRole('dialog', { name: '게임 설정 저장' });
        expect(dialog).toHaveTextContent('사람 장수 상한: 30 → 24');
        expect(calls(fake, 'PATCH')).toEqual([]);
        fireEvent.click(within(dialog).getByRole('button', { name: '저장' }));
        await within(panel).findByText('저장했습니다.');
        expect(calls(fake, 'PATCH')).toEqual([{ path: '/api/game/api/admin/game-settings?server=pep', body: { values: { maxgeneral: 24 } } }]);
    });

    it('게임 설정 조회 실패는 「설정 조회 중…」이 아니라 오류 줄', async () => {
        stubFetch((path) => (path.startsWith('/api/game/api/admin/game-settings') ? new Response(null, { status: 500 }) : undefined));
        await openServerTab();
        const panel = await screen.findByRole('region', { name: '게임 설정' });
        expect(await within(panel).findByText('게임 설정을 불러오지 못했습니다')).toBeInTheDocument();
        expect(within(panel).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
        expect(screen.queryByText(/설정 조회 중/)).toBeNull();
    });

    it('환경값: 뺀 리셋 칸의 RESET_* 는 숨기고, 비밀값 저장은 확인을 받으며 값을 보이지 않는다, 실패는 적갈', async () => {
        const fake = stubFetch((path, init) => (init?.method === 'PATCH' && path === '/api/proxy/admin/env/shared' ? json({ ok: false, message: '저장 거절' }, 400) : undefined));
        await openServerTab();
        const env = await screen.findByRole('region', { name: '환경값' });
        await within(env).findByText('RESET_TURNTERM');
        for (const gone of ['RESET_SYNC', 'RESET_NPCMODE']) expect(within(env).queryByText(gone)).toBeNull();

        const shared = within(env).getByRole('region', { name: '공유 스택' });
        fireEvent.change(within(shared).getByLabelText('ADMIN_PASSWORD'), { target: { value: 'new-secret' } });
        fireEvent.click(within(shared).getByRole('button', { name: '저장' }));
        const dialog = screen.getByRole('dialog', { name: '비밀값 저장' });
        expect(dialog).toHaveTextContent('ADMIN_PASSWORD');
        expect(dialog.textContent).not.toContain('new-secret');
        expect(calls(fake, 'PATCH')).toEqual([]);
        fireEvent.click(within(dialog).getByRole('button', { name: '저장' }));
        const failed = await within(env).findByText('저장 거절');
        expect(failed).toHaveAttribute('role', 'alert');
        expect(failed).toHaveClass('gw31-alert');
        expect(calls(fake, 'PATCH')).toEqual([{ path: '/api/proxy/admin/env/shared', body: { values: { ADMIN_PASSWORD: 'new-secret' } } }]);
    });

    it('환경값 조회 실패는 「환경 설정 조회 중…」에 머물지 않는다', async () => {
        stubFetch((path) => (path === '/api/proxy/admin/env/shared' ? new Response(null, { status: 503 }) : undefined));
        await openServerTab();
        const env = await screen.findByRole('region', { name: '환경값' });
        expect(await within(env).findByText('공유 스택 환경값을 불러오지 못했습니다')).toBeInTheDocument();
        expect(screen.queryByText(/환경 설정 조회 중/)).toBeNull();
        await waitFor(() => expect(within(env).getByText('IMAGE_TAG')).toBeInTheDocument());
    });
});
