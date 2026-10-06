import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// 운영 콘솔 서버 래퍼 — 서버가 비운영자로 확정한 세션에는 운영 콘솔을 내려보내지 않고 같은 「권한 없음」 화면을 그린다.
// 세션을 확정하지 못하면(access 만료 등) 지금처럼 화면(AuthGate)이 /api/auth/me 로 확인한다.
const session = vi.hoisted(() => ({ user: null as null | { role: string } }));
vi.mock('@/lib/auth', () => ({ getSession: async () => session.user }));
vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));

import AdminLayout from '@/app/admin/layout';

const CONSOLE = <p>운영 콘솔 본문</p>;

describe('운영 콘솔 서버 래퍼', () => {
  beforeEach(() => { session.user = null; });

  it('운영자면 콘솔을 그대로 그린다(양성 대조)', async () => {
    session.user = { role: 'ADMIN' };
    render(await AdminLayout({ children: CONSOLE }));
    expect(screen.getByText('운영 콘솔 본문')).toBeInTheDocument();
  });

  it('서버가 비운영자로 확정하면 콘솔 대신 「권한 없음」과 로비 고리', async () => {
    session.user = { role: 'USER' };
    render(await AdminLayout({ children: CONSOLE }));
    expect(screen.queryByText('운영 콘솔 본문')).toBeNull();
    expect(screen.getByText('운영자만 볼 수 있습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로비로' })).toHaveAttribute('href', '/lobby');
  });

  it('세션을 확정하지 못하면 지금처럼 화면 쪽 확인에 맡긴다', async () => {
    render(await AdminLayout({ children: CONSOLE }));
    expect(screen.getByText('운영 콘솔 본문')).toBeInTheDocument();
  });
});
