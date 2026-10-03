// 황실(P-K09) 화면 — 소재지 응답 상태별 모양, 이름은 가진 자료로만, 나머지 영역은 서버 대기 A.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { rememberProvinceNames, resetProvinceNames } from '@opensamguk/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    presence: vi.fn<(signal?: AbortSignal) => Promise<Response>>(),
    mapPreview: vi.fn(),
    session: { generalId: 7 as number | null, frontInfo: { general: { name: '하후돈' } } as unknown },
}));
vi.mock('@/lib/api', () => ({ api: { imperialPresenceResponse: mocks.presence, mapPreview: mocks.mapPreview } }));
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
});
