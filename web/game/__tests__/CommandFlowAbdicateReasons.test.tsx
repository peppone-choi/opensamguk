import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';
import type { PoliticalOption } from '../lib/types';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

type Target = NonNullable<PoliticalOption['targets']>[number];
const NEUTRAL = '지금 선택할 수 있는 대상 장수가 없습니다.';
const DROPPED = '이어받은 물려받을 사람은 이 명령에서 고를 수 없는 곳이라 비웠습니다.';
const sameNation: Target = { generalId: 8, name: '관우', available: false, code: 'SAME_NATION_REQUIRED', reason: '같은 세력의 장수를 선택해 주세요.' };
const noConsent: Target = { generalId: 9, name: '장비', available: false, code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.' };
const declined: Target = { ...noConsent, code: 'CONSENT_DECLINED', reason: '대상 장수가 거절했습니다.' };
const accepted: Target = { generalId: 9, name: '장비', available: true };
const eligible: Target = { generalId: 10, name: '조운', available: true };

/** Mirrors PoliticalOptionsService: with nothing selectable, the first candidate's failure is the top-level one. */
function abdicate(targets: Target[]): PoliticalOption {
    const any = targets.some(t => t.available);
    return { inputId: 'action.abdicate', available: any, targets,
        code: any ? null : targets[0]?.code ?? 'CONSENT_REQUIRED', reason: any ? null : targets[0]?.reason ?? '대상 장수의 수락이 필요합니다.' };
}

function serve(initial: Target[]) {
    const state = { targets: initial, reads: 0, posts: [] as unknown[] };
    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status,
        headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
        const path = new URL(input, 'http://localhost').pathname;
        if (path === '/api/game/api/reserved-commands') return response({ result: true, generalId: 7, slots: [] });
        if (path === '/api/game/api/commands/political-options') {
            state.reads += 1;
            return response([abdicate(state.targets)]);
        }
        if (path.startsWith('/api/game/api/command/') && init?.method === 'POST') {
            state.posts.push(JSON.parse(String(init.body)));
            return response({ status: 'AVAILABLE', requestId: 'abdicate-test', turnIdx: 0 }, 202);
        }
        return response({}, 404);
    }));
    return state;
}

const flow = () => screen.getByTestId('command-flow');
const submit = () => flow().querySelector('[data-input-status]')!;
const topReason = () => flow().querySelector('.os-ia__why')?.textContent ?? null;
const row = (generalId: number) => flow().querySelector(`[data-general-id="${generalId}"]`)!;

async function blocked() {
    await waitFor(() => expect(submit()).toHaveAttribute('data-input-status', 'BLOCKED'));
}

test.each([
    ['관우 먼저', [sameNation, noConsent]],
    ['장비 먼저', [noConsent, sameNation]],
] as [string, Target[]][])('모든 후보가 막히면 순서(%s)와 무관하게 중립 사유를 쓰고 후보 사유는 후보에만 둔다', async (_, targets) => {
    const state = serve(targets);
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await blocked();
    expect(topReason()).toBe(NEUTRAL);
    expect(row(8)).toHaveAttribute('aria-disabled', 'true');
    expect(row(8)).toHaveTextContent('같은 세력의 장수를 선택해 주세요.');
    expect(row(9)).toHaveTextContent('대상 장수의 수락이 필요합니다.');
    expect(row(8)).not.toHaveTextContent('대상 장수의 수락이 필요합니다.');
    expect(row(9)).not.toHaveTextContent('같은 세력의 장수를 선택해 주세요.');
    fireEvent.click(row(9));
    fireEvent.click(submit());
    expect(state.posts).toEqual([]);
});

test('섞인 후보에서는 가능한 후보만 고를 수 있고 막힌 후보 사유를 위로 올리지 않는다', async () => {
    serve([sameNation, eligible, noConsent]);
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await waitFor(() => expect(submit()).toHaveAttribute('data-input-status', 'AVAILABLE'));
    expect(topReason()).toBeNull();
    fireEvent.click(row(10));
    await waitFor(() => expect(row(10)).toHaveAttribute('aria-selected', 'true'));
    fireEvent.click(row(8));
    expect(row(10)).toHaveAttribute('aria-selected', 'true');
    expect(row(8)).toHaveTextContent('같은 세력의 장수를 선택해 주세요.');
});

test('고른 후계자가 다시 읽은 뒤 수락을 거두면 중립 사유로 막히고 보내지 않는다', async () => {
    const state = serve([sameNation, accepted]);
    const first = render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await waitFor(() => expect(submit()).toHaveAttribute('data-input-status', 'AVAILABLE'));
    fireEvent.click(row(9));
    await waitFor(() => expect(row(9)).toHaveAttribute('aria-selected', 'true'));
    first.unmount();

    // The target declined after the first read; reopening the flow on the same heir reads again.
    state.targets = [declined, sameNation];
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" initialTarget={{ kind: 'general', id: '9' }} onClose={vi.fn()} />);
    await blocked();
    expect(state.reads).toBe(2);
    expect(topReason()).toBe(NEUTRAL);
    expect(row(9)).toHaveAttribute('aria-disabled', 'true');
    expect(row(9)).toHaveTextContent('대상 장수가 거절했습니다.');
    expect(await screen.findByText(DROPPED)).toBeInTheDocument();
    fireEvent.click(submit());
    expect(state.posts).toEqual([]);
});

test('다른 후계자가 남아 있어도 막힌 이전 선택은 비우고 보내지 않는다', async () => {
    const state = serve([noConsent, eligible]);
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" initialTarget={{ kind: 'general', id: '9' }} onClose={vi.fn()} />);
    await waitFor(() => expect(submit()).toHaveAttribute('data-input-status', 'AVAILABLE'));
    expect(await screen.findByText(DROPPED)).toBeInTheDocument();
    expect(row(9)).toHaveAttribute('aria-selected', 'false');
    // Retry until the 12-slot read lands; until then submit only reports that slots are loading.
    await waitFor(() => {
        fireEvent.click(submit());
        expect(screen.getByText('「물려받을 사람」을 고르세요.')).toBeInTheDocument();
    });
    expect(state.posts).toEqual([]);
});

test('수락이 생긴 뒤 다시 열면 그 후계자를 고를 수 있다', async () => {
    const state = serve([sameNation, noConsent]);
    const first = render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await blocked();
    first.unmount();
    state.targets = [sameNation, accepted];
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await waitFor(() => expect(submit()).toHaveAttribute('data-input-status', 'AVAILABLE'));
    expect(topReason()).toBeNull();
    fireEvent.click(row(9));
    await waitFor(() => expect(row(9)).toHaveAttribute('aria-selected', 'true'));
});

test('주공이 아니면 모든 후보가 같은 공통 사유로 막히고 그 서버 문장을 그대로 쓴다', async () => {
    const notLord = (t: Target): Target => ({ ...t, available: false, code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.' });
    serve([notLord(eligible), notLord(sameNation)]);
    render(<CommandFlow generalId={7} initialInputId="action.abdicate" onClose={vi.fn()} />);
    await blocked();
    expect(topReason()).toBe('주공만 세력을 해산할 수 있습니다.');
});
