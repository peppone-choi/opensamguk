import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';
import MyBossPage from '@/app/game/my-boss/page';

const mocks = vi.hoisted(() => ({
  myBoss: vi.fn(),
  appoint: vi.fn(),
  kick: vi.fn(),
  changePermission: vi.fn(),
  submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
  default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/StatusBadge', () => ({
  default: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

vi.mock('@/lib/api', () => ({
  api: {
    myBoss: mocks.myBoss,
    commands: {
      appoint: mocks.appoint,
      kick: mocks.kick,
      changePermission: mocks.changePermission,
    },
  },
}));

vi.mock('@/lib/commandSubmit', () => ({
  submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

const baseBoss = {
  result: true,
  nationId: 1,
  hasBoss: true,
  bossGeneralId: 1,
  bossName: '조조',
  bossOfficerLevel: 12,
  nationName: '위',
  nationLevel: 7,
  myGeneralId: 10,
  myOfficerLevel: 5,
  myPermission: 2,
  canManagePersonnel: true,
  roster: [
    {
      generalId: 1,
      name: '조조',
      npcState: 0,
      cityId: 5,
      cityName: '허창',
      officerCityId: 0,
      officerLevel: 12,
      officerLevelText: '황제',
      leadership: 95,
      strength: 80,
      intel: 90,
      politics: 91,
      charm: 76,
      permissionRole: 'normal',
      canBeAppointed: false,
      canBeKicked: false,
      canBeAmbassador: false,
      canBeAuditor: false,
    },
    {
      generalId: 10,
      name: '순욱',
      npcState: 0,
      cityId: 5,
      cityName: '허창',
      officerCityId: 0,
      officerLevel: 5,
      officerLevelText: '사도',
      leadership: 70,
      strength: 40,
      intel: 96,
      permissionRole: 'ambassador',
      canBeAppointed: false,
      canBeKicked: false,
      canBeAmbassador: true,
      canBeAuditor: true,
    },
    {
      generalId: 42,
      name: '허저',
      npcState: 0,
      cityId: 5,
      cityName: '허창',
      officerCityId: 0,
      officerLevel: 1,
      officerLevelText: '일반',
      leadership: 72,
      strength: 96,
      intel: 40,
      permissionRole: 'normal',
      canBeAppointed: true,
      canBeKicked: true,
      canBeAmbassador: true,
      canBeAuditor: true,
    },
    {
      generalId: 43,
      name: '정욱',
      npcState: 0,
      cityId: 5,
      cityName: '허창',
      officerCityId: 0,
      officerLevel: 1,
      officerLevelText: '일반',
      leadership: 62,
      strength: 35,
      intel: 92,
      permissionRole: 'normal',
      canBeAppointed: true,
      canBeKicked: true,
      canBeAmbassador: true,
      canBeAuditor: true,
    },
  ],
  chiefSlots: [
    {
      officerLevel: 11,
      officerLevelText: '승상',
      slotType: 'chief',
      cityId: null,
      cityName: null,
      locked: false,
      assignedGeneralId: null,
      assignedName: null,
      assignedNpcState: null,
    },
  ],
  citySlots: [
    {
      officerLevel: 4,
      officerLevelText: '태수',
      slotType: 'city',
      cityId: 5,
      cityName: '허창',
      locked: false,
      assignedGeneralId: null,
      assignedName: null,
      assignedNpcState: null,
    },
  ],
};

describe('MyBossPage personnel commands', () => {
  let confirmSpy: MockInstance<typeof window.confirm>;

  beforeEach(() => {
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.myBoss.mockReset();
    mocks.appoint.mockReset();
    mocks.kick.mockReset();
    mocks.changePermission.mockReset();
    mocks.submitCommandAndAwaitResult.mockReset();
    mocks.myBoss.mockResolvedValue(baseBoss);
    mocks.appoint.mockResolvedValue({ status: 'AVAILABLE', requestId: 'appoint-1' });
    mocks.kick.mockResolvedValue({ status: 'AVAILABLE', requestId: 'kick-1' });
    mocks.changePermission.mockResolvedValue({ status: 'AVAILABLE', requestId: 'perm-1' });
    mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
      await submit();
      return {
        status: 'applied',
        result: { status: 'RESOLVED', requestId: 'ok-1', ok: true, type: 'personnel', result: {} },
      };
    });
  });

  afterEach(() => {
    confirmSpy.mockRestore();
  });

  it('shows politics and charm in the nation roster', async () => {
    render(<MyBossPage />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '세력 장수' })).toBeInTheDocument());

    expect(screen.getByRole('columnheader', { name: '정치' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '매력' })).toBeInTheDocument();

    const row = screen.getByRole('cell', { name: '조조' }).closest('tr');
    expect(row).not.toBeNull();
    expect(within(row!).getByRole('cell', { name: '91' })).toBeInTheDocument();
    expect(within(row!).getByRole('cell', { name: '76' })).toBeInTheDocument();
  });

  it('hides personnel submit controls for non-chief callers', async () => {
    mocks.myBoss.mockResolvedValue({ ...baseBoss, myOfficerLevel: 1, myPermission: 0, canManagePersonnel: false });

    render(<MyBossPage />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '내 상관' })).toBeInTheDocument());
    expect(screen.getByText('수뇌부만 인사 명령을 실행할 수 있습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '임명' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '추방' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '외교권자 저장' })).not.toBeInTheDocument();
  });

  it('submits appoint through submitCommandAndAwaitResult', async () => {
    const user = userEvent.setup();
    render(<MyBossPage />);

    await waitFor(() => expect(screen.getByRole('button', { name: '임명' })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('임명 직책'), 'city:5:4');
    await user.selectOptions(screen.getByLabelText('임명 대상'), '42');
    await user.click(screen.getByRole('button', { name: '임명' }));

    await waitFor(() => {
      expect(mocks.appoint).toHaveBeenCalledWith({ officerLevel: 4, destGeneralID: 42, destCityID: 5 }, 10);
    });
    expect(mocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(1);
  });

  it('submits kick through submitCommandAndAwaitResult', async () => {
    const user = userEvent.setup();
    render(<MyBossPage />);

    await waitFor(() => expect(screen.getByRole('button', { name: '추방' })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('추방 대상'), '43');
    await user.click(screen.getByRole('button', { name: '추방' }));

    await waitFor(() => {
      expect(mocks.kick).toHaveBeenCalledWith({ destGeneralID: 43 }, 10);
    });
    expect(mocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(1);
    expect(confirmSpy).toHaveBeenCalledWith('정욱을(를) 추방하시겠습니까?');
  });

  it('submits changePermission through submitCommandAndAwaitResult', async () => {
    const user = userEvent.setup();
    render(<MyBossPage />);

    await waitFor(() => expect(screen.getByRole('button', { name: '외교권자 저장' })).toBeInTheDocument());
    const ambassadorPicker = screen.getByRole('group', { name: '외교권자' });
    // page.tsx:122-125 pre-selects roster members whose permissionRole === 'ambassador' (순욱, id 10).
    await waitFor(() => expect(within(ambassadorPicker).getByLabelText('순욱')).toBeChecked());
    await user.click(within(ambassadorPicker).getByLabelText('허저'));
    await user.click(screen.getByRole('button', { name: '외교권자 저장' }));

    await waitFor(() => {
      // page.tsx:132-135 toggleSelection appends, page.tsx:202 submits the whole selection.
      expect(mocks.changePermission).toHaveBeenCalledWith({ isAmbassador: true, genlist: [10, 42] }, 10);
    });
    expect(mocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(1);
  });
});
