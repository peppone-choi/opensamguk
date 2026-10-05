// 관직 · 봉신 › 봉신(P-K04) — 서버 고정 응답(C5 #1373)으로 목록 · 상세 · 상납 이력 · 상태 넷 · 서버 대기 칸을 본다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({
    fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
    mapPreview: vi.fn<(signal?: AbortSignal) => Promise<unknown>>(),
}));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame, api: { mapPreview: mocks.mapPreview } }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/${slug}`} {...rest}>{children}</a>,
}));

import VassalsTab from '@/components/offices/VassalsTab';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/court/vassal');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as Record<string, unknown>;
const PREVIEW = { cities: [{ id: 1, name: '허', displayName: '영천군 허현', level: 1, nationId: 1, x: 0, y: 0 }, { id: 2, name: '양적', displayName: '영천군 양적현', level: 1, nationId: 1, x: 0, y: 0 }], nations: [] };

function serve(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
}

async function open(body: unknown, status = 200) {
    serve(body, status);
    const r = render(<VassalsTab />);
    await act(async () => { await Promise.resolve(); });
    return r;
}

beforeEach(() => {
    mocks.fetchGame.mockReset();
    mocks.mapPreview.mockReset();
    mocks.mapPreview.mockResolvedValue(PREVIEW);
});

describe('VassalsTab', () => {
    it('저장된 계약 — 목록 줄(이름 · 사람 · 봉토 · 상납률 · 이번 달 · 충성)과 상세(조건 · 자치 · 상납 이력)', async () => {
        const { container } = await open(fixture('stored-terms-partial-paid.json'));
        const list = screen.getByRole('region', { name: '봉신 계약' });
        const row = await within(list).findByRole('button', { name: /표본 봉신/ });
        expect(row).toHaveAttribute('aria-pressed', 'true');
        expect(row).toHaveTextContent('사람');
        await waitFor(() => expect(row).toHaveTextContent('봉토 영천군 허현 · 영천군 양적현 · 상납 20%'));
        expect(row).toHaveTextContent('이번 달 완납');
        expect(row).toHaveTextContent('충성 70');
        const detail = screen.getByRole('region', { name: '표본 봉신 — 봉신 계약' });
        expect(detail).toHaveTextContent('봉토 현 2곳 — 영천군 허현 · 영천군 양적현');
        expect(detail).toHaveTextContent('20% — 봉토 수입에서');
        expect(detail).toHaveTextContent('80명');
        expect(detail).toHaveTextContent('군주 승인 뒤');
        for (const label of ['현 방침', '세금 배분', '수비군 지휘']) expect(within(detail).getByText(label)).toBeInTheDocument();
        const history = within(detail).getByRole('table');
        expect(within(history).getByText('200년 1월')).toBeInTheDocument();
        expect(within(history).getByText('완납')).toBeInTheDocument();
        expect(within(detail).getByRole('link', { name: '창고망 보기' })).toHaveAttribute('href', '/game/territory/supply');
        // 서버가 아직 판정하지 않는 칸: 원군 응답 기한 · 맺은 때 · 지금 유효한지 · 봉신 세우기(K8-04), 받은 제안(K8-02) · 원군 요청(K8-17)
        expectServerWait(container, ['K8-04', 'K8-02', 'K8-17']);
        expect(within(detail).getAllByText(/준비 중/)).toHaveLength(3);
        expect(container.textContent).not.toMatch(/200년 \d+월 \d순 맺음|계약 변경|계약 끝내기|봉신으로 세우기/);
    });

    it('상태 넷 — 없음(READY 빈 목록) · 준비 안 됨(NOT_SEEDED) · 셈 못 함(UNAVAILABLE, 다시 읽기) · 읽기 실패(403, 다시 시도)', async () => {
        const empty = await open(fixture('valid-stored-empty.json'));
        expect(screen.getByText('봉신 계약이 없습니다')).toBeInTheDocument();
        empty.unmount();
        const seeded = await open(fixture('not-seeded.json'));
        expect(screen.getByText('봉신 계약 정보가 아직 준비되지 않았습니다')).toBeInTheDocument();
        expect(screen.queryByText('봉신 계약이 없습니다')).toBeNull();
        seeded.unmount();
        const unavailable = await open(fixture('unavailable.json'));
        expect(screen.getByText('계약 정보를 셈하지 못했습니다')).toBeInTheDocument();
        const before = mocks.fetchGame.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '다시 읽기' }));
        await waitFor(() => expect(mocks.fetchGame).toHaveBeenCalledTimes(before + 1));
        unavailable.unmount();
        mocks.fetchGame.mockReset();
        await open({ error: { code: 'FORBIDDEN', message: '본인 장수로만 조회할 수 있습니다.' } }, 403);
        expect(screen.getByText('봉신 계약을 지금 읽을 수 없습니다')).toBeInTheDocument();
        expect(screen.queryByText(/본인 장수로만/)).toBeNull(); // 서버 원문을 싣지 않는다
        expect(mocks.mapPreview).not.toHaveBeenCalled(); // 계약이 없으면 지도 미리보기를 받지 않는다
    });

    it('끝난 계약은 「끝난 계약」 묶음 · 「끝남」, 이번 달 칩 없음', async () => {
        await open(fixture('ended-record-calendar-unavailable.json'));
        const group = await screen.findByLabelText('끝난 계약');
        const row = within(group).getByRole('button', { name: /표본 봉신/ });
        expect(row).toHaveTextContent('끝남');
        expect(row).not.toHaveTextContent('이번 달');
        expect(screen.getByRole('region', { name: '표본 봉신 — 봉신 계약' })).toHaveTextContent('끝난 계약');
    });

    it('이름 · 사람 여부를 짓지 않는다 — 이름 null 은 「이름을 아직 모릅니다」, 사람 여부 미확인은 칩 없이 서버 대기, NPC 는 칩 없음', async () => {
        const a = await open(fixture('human-known-name-unknown.json'));
        expect(await screen.findByRole('button', { name: /이름을 아직 모릅니다/ })).toHaveTextContent('사람');
        a.unmount();
        const b = await open(fixture('identity-unavailable.json'));
        const row = await screen.findByRole('button', { name: /표본 봉신/ });
        expect(within(row).queryByText('사람')).toBeNull();
        expect(within(row).getByText('사람 여부 준비 중')).toHaveAttribute('data-server-wait', 'K8-04');
        b.unmount();
        await open(fixture('verified-unowned.json'));
        const npc = await screen.findByRole('button', { name: /표본 봉신/ });
        expect(within(npc).queryByText('사람')).toBeNull();
        expect(within(npc).queryByText('사람 여부 준비 중')).toBeNull();
    });

    it('미납 달은 낸 양 / 낼 양 · 미납 칩, 영수증이 없으면 「이번 달 아직」 · 「상납 이력이 아직 없습니다」', async () => {
        const a = await open(fixture('unpaid-one-resource.json'));
        const history = await screen.findByRole('table');
        expect(within(history).getByText('미납')).toBeInTheDocument();
        expect(history.querySelectorAll('td[class*="unpaid"]').length).toBeGreaterThan(0);
        expect(screen.getByRole('button', { name: /표본 봉신/ })).toHaveTextContent('이번 달 미납');
        a.unmount();
        await open(fixture('no-monthly-receipt.json'));
        expect(await screen.findByRole('button', { name: /표본 봉신/ })).toHaveTextContent('이번 달 아직');
        expect(screen.getByText('상납 이력이 아직 없습니다.')).toBeInTheDocument();
    });
});
