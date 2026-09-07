import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PartialReservedCommand from '@/components/game/PartialReservedCommand';

const mocks = vi.hoisted(() => ({ reservedCommands: vi.fn(), myBattlePlans: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: { reservedCommands: mocks.reservedCommands, myBattlePlans: mocks.myBattlePlans, commandQueue: { push: vi.fn(), repeat: vi.fn() } } }));
vi.mock('@/components/CommandModal', () => ({ default: () => null }));

describe('PartialReservedCommand 「봉인」 링크 (Phase 4X-C S11·R14)', () => {
    beforeEach(() => {
        mocks.reservedCommands.mockReset(); mocks.myBattlePlans.mockReset();
        mocks.reservedCommands.mockResolvedValue({
            result: true, generalId: 10, year: 200, month: 3, turnPhase: 2, turnTime: '2026-09-06 23:00:00', turnTerm: 60,
            slots: [
                { turnIdx: 0, action: 'che_출병', brief: '출병(호뢰관)', arg: { destCityID: 31 } },
                { turnIdx: 1, action: 'che_출병', brief: '출병(?)', arg: { destCityID: '31' } },
                { turnIdx: 2, action: 'che_출병', brief: '출병(낙양)', arg: { destCityID: 9 } },
                { turnIdx: 3, action: 'che_이동', brief: '이동(허창)', arg: { destCityID: 3 } },
            ],
        });
        mocks.myBattlePlans.mockResolvedValue({ generalId: 10, plans: [{ id: 5, targetCityId: 31, sealed: true }, { id: 6, targetCityId: 9, sealed: false }], rules: {} });
    });

    it('links only che_출병 slots with a numeric destCityID; sealed plans show the 봉인됨 chip, autorun makes it dashed', async () => {
        render(<PartialReservedCommand generalId={10} onToast={() => {}} battlePlanHref="/game/pep/battle-plan" autorunNotice />);
        const sealed = await screen.findByRole('link', { name: '봉인됨' });
        expect(sealed).toHaveAttribute('href', '/game/pep/battle-plan?city=31');
        expect(sealed.className).toContain('rcp-seal--autorun');
        const links = screen.getAllByRole('link', { name: '봉인' });
        expect(links).toHaveLength(1);
        expect(links[0]).toHaveAttribute('href', '/game/pep/battle-plan?city=9');
        expect(screen.queryByText('출병(?)')?.querySelector('a')).toBeNull();
    });

    it('renders no link at all without battlePlanHref', async () => {
        render(<PartialReservedCommand generalId={10} onToast={() => {}} />);
        expect(await screen.findByText('출병(호뢰관)')).toBeInTheDocument();
        expect(screen.queryByRole('link', { name: /봉인/ })).toBeNull();
        expect(mocks.myBattlePlans).not.toHaveBeenCalled();
    });
});
