'use client';

import type { ReactNode } from 'react';
import MemberHeader from '@/components/gateway/MemberHeader';
import PublicHeader from '@/components/gateway/PublicHeader';
import { useAuth } from '@/lib/auth-context';

/**
 * 커뮤니티 셸(설계서 §3 공통) — 서버 밖 · 계정 단위 공간이라 손님도 읽는다. 로그인했으면 회원 머리줄,
 * 아니면 공개 머리줄 오른쪽이 「로그인」이다(옛 상단바는 손님에게도 「로그아웃」을 보였다).
 */
export default function CommunityShell({ children }: { readonly children: ReactNode }) {
    const { user, loading } = useAuth();
    return (
        <div className="gw31-page">
            {user ? <MemberHeader current="board" /> : <PublicHeader logo action={loading ? undefined : 'login'} />}
            <main className="gw31-board">{children}</main>
        </div>
    );
}
