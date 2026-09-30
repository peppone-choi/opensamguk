// 시야 · 첩보(P-C06) — 단계 글자 · 「N순 전 첩보」 일반 문구 · 역정보 표식 없음 · 첩보 단추는 옵션 상태 · 출처는 서버 대기.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { IntelPanel } from '../components/intel/IntelPanel';
import { toIntelView } from '../lib/intel/intel-model';
import type { ScoutOptions, Visibility } from '../lib/campaign-reads';

const vision: Visibility = {
    status: 'READY',
    commanderies: [
        { no: 1, id: 'c1', name: '영천군', tier: 'FULL' },
        { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
        { no: 3, id: 'c3', name: '양국', tier: 'FOG' },
        { no: 4, id: 'c4', name: '패국', tier: 'INTEL', ageTurns: 9 },
    ],
};
const scout: ScoutOptions = {
    status: 'READY', available: true,
    options: [
        { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true },
        { no: 3, id: 'c3', name: '양국', tier: 'FOG', available: false, code: 'TOO_FAR', reason: '너무 멉니다' },
    ],
};

describe('시야 모델', () => {
    it('단계별 묶음 · 첩보는 오래된 것부터 · 첩보 후보만 단추', () => {
        const v = toIntelView(vision, scout);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.groups.map((g) => [g.tier, g.rows.map((r) => r.name)])).toEqual([['INTEL', ['패국', '진류군']], ['FOG', ['양국']], ['FULL', ['영천군']]]);
        expect(v.groups[2].rows[0].scout).toBeNull();
        expect(toIntelView({ status: 'UNAVAILABLE' }, scout)).toEqual({ state: 'unreadable', status: 'UNAVAILABLE' });
    });
});

describe('시야 · 첩보 칸', () => {
    it('「N순 전 첩보」 일반 문구만 — 역정보 표식이 없다', () => {
        const { container } = render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, scout), onRetry: vi.fn() }} onScout={vi.fn()} />);
        expect(screen.getByText('3순 전 첩보 · 다시 첩보하면 갱신됩니다')).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/가짜|역정보|의심/);
        expect(screen.getByText('시야 출처 준비 중')).toBeInTheDocument();
    });

    it('첩보 단추 — 가능하면 명령 흐름을 열고, 막히면 서버 사유', () => {
        const onScout = vi.fn();
        render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, scout), onRetry: vi.fn() }} onScout={onScout} />);
        const intel = within(screen.getByRole('region', { name: '첩보' }));
        fireEvent.click(within(intel.getByText('진류군').closest('li')!).getByRole('button', { name: '첩보' }));
        expect(onScout).toHaveBeenCalledWith(expect.objectContaining({ id: 'c2' }));
        const fog = within(within(screen.getByRole('region', { name: '안 보임' })).getByText('양국').closest('li')!);
        expect(fog.getByRole('button', { name: /첩보/ })).toHaveAttribute('data-input-status', 'BLOCKED');
        expect(fog.getAllByText('너무 멉니다').length).toBeGreaterThan(0);
    });

    it('첩보 자체가 막히면(물 위 등) 모든 첩보 단추가 그 사유로 막힌다', () => {
        const blocked = { ...scout, available: false, code: 'POSITION_UNAVAILABLE', reason: '장수가 물 위에 있어 첩보할 수 없습니다' };
        render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, blocked), onRetry: vi.fn() }} onScout={vi.fn()} />);
        expect(screen.getByText('지금은 첩보를 보낼 수 없습니다 — 장수가 물 위에 있어 첩보할 수 없습니다')).toBeInTheDocument();
        const intel = within(screen.getByRole('region', { name: '첩보' }));
        expect(within(intel.getByText('진류군').closest('li')!).getByRole('button', { name: /첩보/ })).toHaveAttribute('data-input-status', 'BLOCKED');
    });
});
