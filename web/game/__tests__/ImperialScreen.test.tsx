// 황실(P-K09) 화면 — 소재지 응답 상태별 모양, 이름은 가진 자료로만, 나머지 영역은 서버 대기 A.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { expectServerWait, expectServerWaitGone, rememberProvinceNames, resetProvinceNames } from '@opensamguk/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    presence: vi.fn<(signal?: AbortSignal) => Promise<Response>>(),
    court: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
    mapPreview: vi.fn(),
    session: { generalId: 7 as number | null, frontInfo: { general: { name: '하후돈' } } as unknown },
}));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.court, api: { imperialPresenceResponse: mocks.presence, mapPreview: mocks.mapPreview } }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => mocks.session }));

import ImperialScreen from '@/components/imperial/ImperialScreen';

const FIXTURES = resolve(__dirname, '../../../app/game-api/src/test/resources/imperial');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(FIXTURES, name), 'utf8')) as Record<string, unknown>;
const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
const badge = (over: Record<string, unknown> = {}) => ({
    lineCode: 'han', lineName: '한', emperorGeneralId: 101, emperorName: '유협', emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: '70930', emperorCityId: 12, courtCityId: 11, ...over,
});
const PREVIEW = { cities: [{ id: 11, name: '허', displayName: '영천군 허현' }, { id: 12, name: '낙양', displayName: '하남윤 낙양현' }], nations: [] };

async function settle() {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

beforeEach(() => {
    mocks.presence.mockReset();
    mocks.court.mockReset();
    mocks.court.mockImplementation(() => respond({}, 404)); // court(C6 #1389)가 main 에 오기 전 — 서버 대기
    mocks.mapPreview.mockReset();
    mocks.mapPreview.mockResolvedValue(PREVIEW);
    mocks.session = { generalId: 7, frontInfo: { general: { name: '하후돈' } } };
});
afterEach(() => resetProvinceNames());

describe('ImperialScreen', () => {
    it('황실 없음(NOT_SEEDED) — 빈 상태와 칭제 칸만, 서버 대기 영역 · 지도 미리보기 없음', async () => {
        mocks.presence.mockImplementation(() => respond(fixture('presence-not-seeded.json')));
        render(<ImperialScreen />);
        await settle();
        expect(screen.getByText('이 천하에는 황실이 없습니다')).toBeInTheDocument();
        expect(screen.queryByText('세력과 황실')).toBeNull();
        expect(screen.getByRole('heading', { name: '칭제' })).toBeInTheDocument();
        expect(mocks.mapPreview).not.toHaveBeenCalled();
    });

    it('읽기 실패(409 STATE_UNAVAILABLE) — 빈 것과 다른 오류 모양, 다시 시도하면 다시 읽는다', async () => {
        mocks.presence.mockImplementationOnce(() => respond(fixture('presence-unavailable.json'), 409));
        mocks.presence.mockImplementationOnce(() => respond(fixture('presence-not-seeded.json')));
        const { container } = render(<ImperialScreen />);
        await settle();
        expect(screen.getByText('황실 정보를 지금 읽을 수 없습니다')).toBeInTheDocument();
        expect(container.querySelector('.os-status--error')).not.toBeNull();
        expect(container.textContent).toContain('STATE_UNAVAILABLE');
        expect(screen.queryByRole('heading', { name: '칭제' })).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        await settle();
        expect(mocks.presence).toHaveBeenCalledTimes(2);
        expect(screen.getByText('이 천하에는 황실이 없습니다')).toBeInTheDocument();
    });

    it('연결 실패 · 모르는 응답도 오류다(성공으로 치지 않는다)', async () => {
        mocks.presence.mockImplementationOnce(() => Promise.reject(new TypeError('offline')));
        render(<ImperialScreen />);
        await settle();
        expect(screen.getByText('황실 정보를 지금 읽을 수 없습니다')).toBeInTheDocument();
    });

    it('황제가 있다(READY) — 황통 카드: 황제(서버 이름) · 있는 곳(성 안) · 조정, 城 이름은 지도 미리보기에서. 나머지는 서버 대기 A', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        const { container } = render(<ImperialScreen />);
        await settle();
        const line = screen.getByRole('region', { name: '황통 — 한' });
        expect(within(line).getByText('황제').nextElementSibling).toHaveTextContent('유협');
        expect(within(line).getByText('하남윤 낙양현 · 성 안')).toBeInTheDocument();
        expect(within(line).getByText('영천군 허현')).toBeInTheDocument();
        expect(mocks.mapPreview).toHaveBeenCalledTimes(1);
        for (const title of ['세력과 황실', '조서', '인장 · 조정 방침']) expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
        expect(container.querySelectorAll('.os-status--waiting')).toHaveLength(4); // 대기 셋 + 칭제
        expect(container.textContent).not.toMatch(/호의/); // 세력별 호의는 이 응답에 없다 — 짓지 않는다
    });

    it('서버 대기 영역마다 기다리는 계약판 행을 단다 — 세력과 황실 · 조서 · 인장은 K8-10, 칭제는 K8-15', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        const { container } = render(<ImperialScreen />);
        await settle();
        const rows = Array.from(container.querySelectorAll('.os-status--waiting')).map((el) => el.closest('[data-server-wait]')?.getAttribute('data-server-wait') ?? '(없음)');
        expect(rows).toEqual(['K8-10', 'K8-10', 'K8-10', 'K8-15']);
    });

    it('보드의 칸은 숨기지 않는다 — 섭정 · 지키는 세력 · 조정 상태는 「준비 중」(K8-10), 지도 표식도 「준비 중」 칸', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        render(<ImperialScreen />);
        await settle();
        const line = screen.getByRole('region', { name: '황통 — 한' });
        for (const k of ['섭정', '조정을 지키는 세력', '조정 상태']) {
            const v = within(line).getByText(k).nextElementSibling!;
            expect(v).toHaveTextContent('준비 중');
            expect(v.querySelector('[data-server-wait="K8-10"]')).not.toBeNull();
        }
        expect(within(line).getByText('지도 표식').parentElement).toHaveTextContent('준비 중');
    });

    it('황제 이름이 비면(null) 「이름을 아직 모릅니다」 — 계통명이나 번호로 채우지 않는다', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge({ emperorName: null })] }));
        render(<ImperialScreen />);
        await settle();
        const line = screen.getByRole('region', { name: '황통 — 한' });
        expect(within(line).getByText('황제').nextElementSibling).toHaveTextContent('이름을 아직 모릅니다');
        expect(line.textContent).not.toContain('101');
    });

    it('성 밖은 구역 이름을 알 때만 붙이고, 수역은 「물 위」, 조정이 없으면 「정하지 않음」', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [
            badge({ lineCode: 'a', lineName: '갑', emperorCityId: null, emperorNodeId: '70930' }),
            badge({ lineCode: 'b', lineName: '을', emperorCityId: null, emperorNodeId: '99999' }),
            badge({ lineCode: 'c', lineName: '병', emperorNodeKind: 'WATER_ZONE', emperorNodeId: 'w1', emperorCityId: null, courtCityId: null }),
        ] }));
        act(() => rememberProvinceNames({ provinceRecords: [{ id: '70930', displayName: '영천' }] } as never));
        render(<ImperialScreen />);
        await settle();
        expect(within(screen.getByRole('region', { name: '황통 — 갑' })).getByText('영천 · 성 밖')).toBeInTheDocument();
        expect(within(screen.getByRole('region', { name: '황통 — 을' })).getByText('성 밖')).toBeInTheDocument();
        const water = screen.getByRole('region', { name: '황통 — 병' });
        expect(within(water).getByText('물 위')).toBeInTheDocument();
        expect(within(water).getByText('정하지 않음')).toBeInTheDocument();
    });

    it('지도 미리보기를 못 받으면 城은 「어느 성」 — 화면은 그대로', async () => {
        mocks.mapPreview.mockRejectedValue(new Error('offline'));
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        render(<ImperialScreen />);
        await settle();
        expect(within(screen.getByRole('region', { name: '황통 — 한' })).getByText('어느 성 · 성 안')).toBeInTheDocument();
    });

    it('공위(READY · 배지 없음) — 「지금 황제가 없습니다」와 서버 대기 영역', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [] }));
        render(<ImperialScreen />);
        await settle();
        expect(screen.getByText('지금 황제가 없습니다')).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: '조서' })).toBeInTheDocument();
        expect(mocks.mapPreview).not.toHaveBeenCalled();
    });

    // ── court(D123, C6 #1389) — 서버가 main 에 들어오면 별도 PR 없이 값이 나온다(D124 미리 짓기). 모양은 docs/design/imperial-court-api.md.
    const courtLine = (over: Record<string, unknown> = {}) => ({
        code: 'han', name: '한', status: 'ACTIVE', holderGeneralId: 101, emperorName: '유협', courtCityId: 11, courtCityName: '허현',
        regentGeneralId: null, regentName: null, courtNationId: 1, courtNationName: '조조',
        fieldStates: { holder: 'READY', courtCity: 'READY', regent: 'READY', courtNation: 'READY' }, ...over,
    });
    const quiet = (code: string, name: string, status: string) => ({
        code, name, status, holderGeneralId: null, emperorName: null, courtCityId: null, courtCityName: null, regentGeneralId: null, regentName: null,
        courtNationId: null, courtNationName: null, fieldStates: { holder: 'NOT_APPLICABLE', courtCity: 'NOT_APPLICABLE', regent: 'NOT_APPLICABLE', courtNation: 'NOT_APPLICABLE' },
    });

    it('court 가 오면 조정 · 섭정 · 지키는 세력이 서버 값으로 — 섭정 미지정은 「—」, 조정 상태만 서버 대기(K8-10)', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        mocks.court.mockImplementation(() => respond({ status: 'READY', lines: [courtLine()] }));
        render(<ImperialScreen />);
        await settle();
        expect(mocks.court).toHaveBeenCalledWith('/api/imperial/court?generalId=7', expect.objectContaining({ cache: 'no-store' }));
        const line = screen.getByRole('region', { name: '황통 — 한' });
        expect(within(line).getByText('조정').nextElementSibling).toHaveTextContent('허현');
        expect(within(line).getByText('섭정').nextElementSibling).toHaveTextContent('—');
        expect(within(line).getByText('조정을 지키는 세력').nextElementSibling).toHaveTextContent('조조');
        // 섭정 · 지키는 세력 칸은 서버 대기 표지가 사라지고 값이 그 칸 밖(표지 없는 칸)에 보인다. 조정 상태 칸 하나만 K8-10 으로 남는다.
        for (const k of ['섭정', '조정을 지키는 세력']) expectServerWaitGone(within(line).getByText(k).nextElementSibling!, ['K8-10']);
        expectServerWaitGone(within(line).getByText('조정을 지키는 세력').nextElementSibling!, ['K8-10'], { value: '조조' });
        expect(line.querySelectorAll('[data-server-wait="K8-10"]')).toHaveLength(1);
        expect(within(line).getByText('조정 상태').nextElementSibling!.querySelector('[data-server-wait="K8-10"]')).not.toBeNull();
    });

    it('court 칸 상태 — 셈하지 못함(UNAVAILABLE) · 정하지 않음(명시 null) · court 읽기 실패는 「지금 읽을 수 없음」', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        mocks.court.mockImplementation(() => respond({ status: 'READY', lines: [courtLine({ regentGeneralId: 5, regentName: null, courtNationId: null, courtNationName: null,
            fieldStates: { holder: 'READY', courtCity: 'READY', regent: 'UNAVAILABLE', courtNation: 'READY' } })] }));
        const { unmount } = render(<ImperialScreen />);
        await settle();
        const line = screen.getByRole('region', { name: '황통 — 한' });
        expect(within(line).getByText('섭정').nextElementSibling).toHaveTextContent('셈하지 못함');
        expect(within(line).getByText('조정을 지키는 세력').nextElementSibling).toHaveTextContent('정하지 않음');
        unmount();
        mocks.court.mockImplementation(() => respond({ status: 'STATE_UNAVAILABLE', lines: [] }, 409));
        render(<ImperialScreen />);
        await settle();
        const again = screen.getByRole('region', { name: '황통 — 한' });
        expect(within(again).getByText('섭정').nextElementSibling).toHaveTextContent('지금 읽을 수 없음');
        expect(screen.queryByText('황실 정보를 지금 읽을 수 없습니다')).toBeNull(); // presence 는 그대로 그린다
    });

    it('공위 · 종결(D123) — 공위는 「황통 — 이름 · 공위」만, 종결은 「이름 황통 · 끝남」 한 줄, 상세 칸 없음', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [] }));
        mocks.court.mockImplementation(() => respond({ status: 'READY', lines: [quiet('han', '한', 'VACANT'), quiet('zhong', '중', 'ENDED')] }));
        render(<ImperialScreen />);
        await settle();
        const vacant = screen.getByRole('list', { name: '공위인 황통' });
        expect(vacant).toHaveTextContent('황통 — 한');
        expect(vacant).toHaveTextContent('공위');
        expect(screen.getByText('지금 황제가 없습니다')).toBeInTheDocument();
        const ended = screen.getByRole('list', { name: '끝난 황통' });
        expect(ended).toHaveTextContent('중 황통');
        expect(ended).toHaveTextContent('끝남');
        expect(document.body.textContent).not.toMatch(/섭정|지키는 세력/); // 공위 · 종결은 상세 칸을 그리지 않는다
    });

    it('court 가 계약 밖이면 받지 않는다 — 키가 빠지거나 공위에 상세 값이 있으면 「지금 읽을 수 없음」', async () => {
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        const missing = courtLine() as Record<string, unknown>;
        delete missing.regentName;
        mocks.court.mockImplementation(() => respond({ status: 'READY', lines: [missing] }));
        const { unmount } = render(<ImperialScreen />);
        await settle();
        expect(within(screen.getByRole('region', { name: '황통 — 한' })).getByText('섭정').nextElementSibling).toHaveTextContent('지금 읽을 수 없음');
        unmount();
        mocks.court.mockImplementation(() => respond({ status: 'READY', lines: [courtLine(), { ...quiet('zhong', '중', 'VACANT'), regentName: '누군가' }] }));
        render(<ImperialScreen />);
        await settle();
        expect(within(screen.getByRole('region', { name: '황통 — 한' })).getByText('섭정').nextElementSibling).toHaveTextContent('지금 읽을 수 없음');
        expect(screen.queryByText('누군가')).toBeNull();
    });

    it('황실이 없으면(NOT_SEEDED) court 를 읽지 않는다 · 장수가 없으면 서버 대기', async () => {
        mocks.presence.mockImplementation(() => respond(fixture('presence-not-seeded.json')));
        const { unmount } = render(<ImperialScreen />);
        await settle();
        expect(mocks.court).not.toHaveBeenCalled();
        unmount();
        mocks.session = { generalId: null, frontInfo: null };
        mocks.presence.mockImplementation(() => respond({ status: 'READY', badges: [badge()] }));
        render(<ImperialScreen />);
        await settle();
        expect(mocks.court).not.toHaveBeenCalled();
        expectServerWait(screen.getByRole('region', { name: '황통 — 한' }), ['K8-10', 'map-layer · crown']);
    });
});
