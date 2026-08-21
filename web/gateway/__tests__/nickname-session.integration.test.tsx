import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AccountPage from '@/app/account/page';
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

describe('nickname session integration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    let meRequests = 0;
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
  });
});
