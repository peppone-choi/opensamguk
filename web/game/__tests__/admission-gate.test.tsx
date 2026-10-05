// /game/{id} 공개 전 화면(D112 ② A안) — 진짜 GameSessionProvider 로 front-info 응답에 따라 gate 가 바뀌는지.
// game-api admission(C1 #1355): 403 SERVER_NOT_PUBLIC · 503 SERVER_ADMISSION_UNAVAILABLE · 본문 {error:{code,message}}.
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameHttpError } from '@/lib/api';
import { admissionOf } from '@/lib/server-admission';
import { GameSessionProvider } from '@/lib/campaign-session';
import AdmissionGate from '@/components/shell/AdmissionGate';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }), usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams() }));

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const FRONT = {
    result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: false, generalId: 0, name: '', nationId: 0, officerLevel: 0, permission: 0, showSecret: false },
    nation: null, city: null, recentRecord: {},
};
let frontInfo: () => Response;

beforeEach(() => {
    document.cookie = 'sam_server=pep; path=/';
    vi.stubGlobal('fetch', vi.fn(async (input: string) => (new URL(input, 'http://x').pathname === '/api/game/api/front-info' ? frontInfo() : json(404, {}))));
});
afterEach(() => vi.unstubAllGlobals());

const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });
const page = () => render(<GameSessionProvider><AdmissionGate><p>작전실</p></AdmissionGate></GameSessionProvider>);

describe('공개 상태 판정', () => {
    it.each<[unknown, string | null]>([
        [new GameHttpError(403, 'SERVER_NOT_PUBLIC', '403: Forbidden'), 'not-public'],
        [new GameHttpError(503, 'SERVER_ADMISSION_UNAVAILABLE', '503: Service Unavailable'), 'unavailable'],
        [new GameHttpError(403, null, '403: Forbidden'), null], // 다른 403(권한) 은 공개 상태가 아니다
        [new GameHttpError(401, 'AUTH_REQUIRED', '401: Unauthorized'), null], // 로그인은 AuthGate 몫
        [new GameHttpError(503, null, '503'), null], // game-api 다운(본문 없음)은 공개 상태가 아니다
        [new Error('403: Forbidden'), null],
    ])('%s → %s', (error, want) => expect(admissionOf(error)).toBe(want));
});

describe('공개 전 화면', () => {
    it('front-info 403 SERVER_NOT_PUBLIC → 게임 화면 대신 「공개되지 않았습니다」 + 로비로', async () => {
        frontInfo = () => json(403, { error: { code: 'SERVER_NOT_PUBLIC', message: '공개 전' } });
        page();
        await settle();
        expect(screen.getByText('이 서버는 지금 공개되지 않았습니다')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: /로비로/ })).toHaveAttribute('href', expect.stringMatching(/\/lobby$/));
        expect(screen.queryByText('작전실')).toBeNull();
    });

    it('front-info 503 SERVER_ADMISSION_UNAVAILABLE → 「확인하지 못했습니다」, 다시 읽어 공개면 게임 화면', async () => {
        frontInfo = () => json(503, { error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '확인 불가' } });
        page();
        await settle();
        expect(screen.getByText('서버 공개 상태를 확인하지 못했습니다')).toBeInTheDocument();
        expect(screen.queryByText('작전실')).toBeNull();
        frontInfo = () => json(200, FRONT);
        fireEvent.click(screen.getByRole('button', { name: /다시/ }));
        await settle();
        expect(screen.getByText('작전실')).toBeInTheDocument();
        expect(screen.queryByText('서버 공개 상태를 확인하지 못했습니다')).toBeNull();
    });

    it('공개(200)거나 공개 상태와 무관한 실패(500 · 코드 없는 403)면 게임 화면 그대로', async () => {
        for (const respond of [() => json(200, FRONT), () => json(500, {}), () => json(403, {})]) {
            frontInfo = respond;
            const view = page();
            await settle();
            expect(screen.getByText('작전실')).toBeInTheDocument();
            expect(screen.queryByText('이 서버는 지금 공개되지 않았습니다')).toBeNull();
            view.unmount();
        }
    });
});
