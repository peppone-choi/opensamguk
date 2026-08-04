import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CharacterClaim from '@/components/game/CharacterClaim';
import type { FrontGlobalInfo } from '@/lib/types';

const apiMocks = vi.hoisted(() => ({
  claimable: vi.fn(),
  claim: vi.fn(),
  pollCommandResult: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    claimable: apiMocks.claimable,
    claim: apiMocks.claim,
  },
  pollCommandResult: apiMocks.pollCommandResult,
}));

const global: FrontGlobalInfo = {
  year: 200,
  month: 1,
  turnterm: 10,
  scenario: '1010',
  scenarioText: '테스트 시나리오',
  generalCount: 1,
  nationCount: 1,
  cityCount: 1,
  npcCount: 1,
  serverId: 'pep',
  npcMode: 1,
  blockGeneralCreate: 1,
};

describe('CharacterClaim', () => {
  beforeEach(() => {
    apiMocks.claim.mockReset();
    apiMocks.pollCommandResult.mockReset();
    apiMocks.claimable.mockReset().mockResolvedValue({
      result: true,
      hasGeneral: false,
      candidates: [
        {
          generalId: 9,
          name: '조조',
          nationId: 1,
          nationName: '위',
          leadership: 80,
          strength: 70,
          intel: 90,
          politics: 95,
          charm: 85,
          picture: null,
          imageServer: 0,
          special: null,
          special2: null,
          personal: null,
        },
      ],
    });
  });

  it('shows every five-stat value on possession cards and in the player hint', async () => {
    render(<CharacterClaim global={global} onClaimed={vi.fn()} />);

    expect(await screen.findByText('통 80')).toBeInTheDocument();
    expect(screen.getByText('무 70')).toBeInTheDocument();
    expect(screen.getByText('지 90')).toBeInTheDocument();
    expect(screen.getByText('정치 95')).toBeInTheDocument();
    expect(screen.getByText('매력 85')).toBeInTheDocument();
    expect(screen.getByText('선택한 장수의 능력치: 통솔 / 무력 / 지력 / 정치 / 매력')).toBeInTheDocument();
  });

  it('waits for a resolved successful daemon result before claiming the character', async () => {
    const onClaimed = vi.fn();
    let resolveTerminal: (result: { status: 'RESOLVED'; requestId: string; ok: boolean; type: string; result: Record<string, unknown> }) => void;
    apiMocks.claim.mockResolvedValue({ result: true, generalId: 9, reason: null, requestId: 'claim-9' });
    apiMocks.pollCommandResult.mockReturnValue(
      new Promise((resolve) => {
        resolveTerminal = resolve;
      }),
    );

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    fireEvent.click(await screen.findByRole('button', { name: '빙의' }));

    await waitFor(() => expect(apiMocks.pollCommandResult).toHaveBeenCalledWith('claim-9'));
    expect(onClaimed).not.toHaveBeenCalled();

    resolveTerminal!({ status: 'RESOLVED', requestId: 'claim-9', ok: true, type: 'claimNpc', result: {} });

    await waitFor(() => expect(onClaimed).toHaveBeenCalledTimes(1));
  });

  it('accepts an idempotent self-owned success without polling for a request ID', async () => {
    const onClaimed = vi.fn();
    apiMocks.claim.mockResolvedValue({
      result: true,
      generalId: 9,
      reason: '이미 점유한 장수입니다.',
      requestId: null,
    });

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    fireEvent.click(await screen.findByRole('button', { name: '빙의' }));

    await waitFor(() => expect(onClaimed).toHaveBeenCalledTimes(1));
    expect(apiMocks.pollCommandResult).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps an immediate failed response as a failure when it has no request ID', async () => {
    const onClaimed = vi.fn();
    apiMocks.claim.mockResolvedValue({
      result: false,
      generalId: null,
      reason: '이미 점유된 장수입니다.',
      requestId: null,
    });

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    fireEvent.click(await screen.findByRole('button', { name: '빙의' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 점유된 장수입니다.');
    expect(onClaimed).not.toHaveBeenCalled();
    expect(apiMocks.pollCommandResult).not.toHaveBeenCalled();
  });

  it('reloads a terminal daemon denial into a retry candidate without resubmitting the claim', async () => {
    const onClaimed = vi.fn();
    apiMocks.claimable
      .mockReset()
      .mockResolvedValueOnce({
        result: true,
        hasGeneral: false,
        candidates: [
          {
            generalId: 9,
            name: '조조',
            nationId: 1,
            nationName: '위',
            leadership: 80,
            strength: 70,
            intel: 90,
            politics: 95,
            charm: 85,
            picture: null,
            imageServer: 0,
            special: null,
            special2: null,
            personal: null,
          },
        ],
      })
      .mockResolvedValueOnce({
        result: true,
        hasGeneral: false,
        reason: '빙의 가능한 장수가 아닙니다.',
        candidates: [
          {
            generalId: 11,
            name: '장료',
            nationId: 1,
            nationName: '위',
            leadership: 70,
            strength: 80,
            intel: 75,
            politics: 60,
            charm: 70,
            picture: null,
            imageServer: 0,
            special: null,
            special2: null,
            personal: null,
          },
        ],
      });
    apiMocks.claim
      .mockResolvedValueOnce({ result: true, generalId: 9, reason: null, requestId: 'claim-9' })
      .mockResolvedValueOnce({ result: false, generalId: 9, reason: '빙의 가능한 장수가 아닙니다.', requestId: null });
    apiMocks.pollCommandResult.mockResolvedValue({
      status: 'RESOLVED',
      requestId: 'claim-9',
      ok: false,
      type: 'claimNpc',
      reason: '빙의 가능한 장수가 아닙니다.',
      result: {},
    });

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    fireEvent.click(await screen.findByRole('button', { name: '빙의' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('빙의 가능한 장수가 아닙니다.');
    expect(await screen.findByText('장료')).toBeInTheDocument();
    expect(onClaimed).not.toHaveBeenCalled();
    await waitFor(() => expect(apiMocks.claim).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(apiMocks.claimable).toHaveBeenCalledTimes(2));
  });

  it('retries a timed-out request through its original request id instead of entering early', async () => {
    const onClaimed = vi.fn();
    apiMocks.claim
      .mockResolvedValueOnce({ result: true, generalId: 9, reason: null, requestId: 'claim-9' })
      .mockResolvedValueOnce({ result: true, generalId: 9, reason: null, requestId: 'claim-9' });
    apiMocks.pollCommandResult
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce({ status: 'RESOLVED', requestId: 'claim-9', ok: true, type: 'claimNpc', result: {} });

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    const button = await screen.findByRole('button', { name: '빙의' });
    fireEvent.click(button);

    expect(await screen.findByRole('alert')).toHaveTextContent('빙의 처리가 지연되고 있습니다. 잠시 후 다시 시도하세요.');
    expect(onClaimed).not.toHaveBeenCalled();

    fireEvent.click(button);

    await waitFor(() => expect(onClaimed).toHaveBeenCalledTimes(1));
    expect(apiMocks.claim).toHaveBeenCalledTimes(2);
    expect(apiMocks.pollCommandResult).toHaveBeenNthCalledWith(1, 'claim-9');
    expect(apiMocks.pollCommandResult).toHaveBeenNthCalledWith(2, 'claim-9');
  });

  it('does not claim the character when the terminal result stays pending', async () => {
    const onClaimed = vi.fn();
    apiMocks.claim.mockResolvedValue({ result: true, generalId: 9, reason: null, requestId: 'claim-9' });
    apiMocks.pollCommandResult.mockResolvedValue(null);

    render(<CharacterClaim global={global} onClaimed={onClaimed} />);
    fireEvent.click(await screen.findByRole('button', { name: '빙의' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('빙의 처리가 지연되고 있습니다. 잠시 후 다시 시도하세요.');
    expect(onClaimed).not.toHaveBeenCalled();
  });
});
