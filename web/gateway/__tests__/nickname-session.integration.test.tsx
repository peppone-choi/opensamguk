import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AccountPage from '@/app/account/page';
import Topbar from '@/components/Topbar';
import { AuthProvider, useAuth } from '@/lib/auth-context';
import type { User } from '@/lib/types';

const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace }) }));

const originalUser: User = {
  id: 1,
  username: 'tester',
  email: null,
  nickname: '예전별명',
  role: 'USER',
  picture: null,
  imageServer: 0,
};

const changedUser: User = { ...originalUser, nickname: '새별명' };
let meRequests = 0;

function RefreshRaceHarness() {
  const { loading, refresh } = useAuth();
  return (
    <>
      <Topbar />
      <output aria-label="세션 로딩 상태">{loading ? 'loading' : 'idle'}</output>
      <button type="button" onClick={() => void refresh()}>세션 조회</button>
      <button type="button" onClick={() => void refresh(changedUser)}>변경 응답 적용</button>
    </>
  );
}

function deferredResponse() {
  let resolve = (_response: Response): void => {
    throw new Error('응답 resolver가 준비되지 않았습니다.');
  };
  let reject = (_reason: Error): void => {
    throw new Error('응답 rejecter가 준비되지 않았습니다.');
  };
  const promise = new Promise<Response>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

describe('nickname session integration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    meRequests = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url === '/api/account/nickname') {
        return new Response(JSON.stringify({ user: changedUser }), { status: 200 });
      }
      if (url === '/api/auth/me') {
        meRequests += 1;
        return new Response(JSON.stringify({ user: meRequests === 1 ? originalUser : changedUser }), { status: 200 });
      }
      throw new Error(`unexpected request: ${url}`);
    }));
  });

  it('renders the refreshed nickname in the topbar after account mutation', async () => {
    render(<AccountPage />);
    const topbar = await screen.findByRole('banner');
    expect(within(topbar).getByText('예전별명')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '새별명' } });
    fireEvent.click(screen.getByRole('button', { name: '닉네임 변경' }));

    await waitFor(() => expect(within(topbar).getByText('새별명')).toBeInTheDocument());
    expect(within(topbar).queryByText('예전별명')).toBeNull();
    expect(meRequests).toBe(1);
  });

  it('keeps the canonical nickname when an older session request resolves later', async () => {
    // Given
    const session = {
      resolve: (_response: Response): void => {
        throw new Error('세션 응답 resolver가 준비되지 않았습니다.');
      },
    };
    const staleSession = new Promise<Response>((resolve) => {
      session.resolve = resolve;
    });
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(staleSession));
    render(
      <AuthProvider initialUser={originalUser}>
        <RefreshRaceHarness />
      </AuthProvider>,
    );

    // When
    fireEvent.click(screen.getByRole('button', { name: '세션 조회' }));
    fireEvent.click(screen.getByRole('button', { name: '변경 응답 적용' }));
    expect(within(screen.getByRole('banner')).getByText('새별명')).toBeInTheDocument();
    await act(async () => {
      session.resolve(new Response(JSON.stringify({ user: originalUser }), { status: 200 }));
      await staleSession;
    });

    // Then
    expect(within(screen.getByRole('banner')).getByText('새별명')).toBeInTheDocument();
    expect(within(screen.getByRole('banner')).queryByText('예전별명')).toBeNull();
  });

  it('keeps the latest session loading and user when an older request rejects later', async () => {
    // Given
    const staleSession = deferredResponse();
    const currentSession = deferredResponse();
    vi.stubGlobal('fetch', vi.fn()
      .mockReturnValueOnce(staleSession.promise)
      .mockReturnValueOnce(currentSession.promise));
    render(
      <AuthProvider initialUser={originalUser}>
        <RefreshRaceHarness />
      </AuthProvider>,
    );

    // When
    fireEvent.click(screen.getByRole('button', { name: '세션 조회' }));
    fireEvent.click(screen.getByRole('button', { name: '세션 조회' }));
    expect(screen.getByLabelText('세션 로딩 상태')).toHaveTextContent('loading');
    await act(async () => {
      staleSession.reject(new Error('stale session failed'));
      await staleSession.promise.catch(() => undefined);
    });

    // Then
    expect(within(screen.getByRole('banner')).getByText('예전별명')).toBeInTheDocument();
    expect(screen.getByLabelText('세션 로딩 상태')).toHaveTextContent('loading');
    await act(async () => {
      currentSession.resolve(new Response(JSON.stringify({ user: changedUser }), { status: 200 }));
      await currentSession.promise;
    });
    expect(within(screen.getByRole('banner')).getByText('새별명')).toBeInTheDocument();
    expect(screen.getByLabelText('세션 로딩 상태')).toHaveTextContent('idle');
  });
});
