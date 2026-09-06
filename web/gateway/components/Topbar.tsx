'use client';

import { Brand } from '@opensamguk/ui';
import Link from 'next/link';
import { useAuthOptional } from '@/lib/auth-context';
import { AUTH_LABELS, LOBBY_LABELS } from '@/lib/constants';

export type TopbarSection = 'lobby' | 'board' | 'account' | 'admin';

// 게이트웨이 상단바(56px) — ADR-LITE-049. 워드마크 28~32px · 로비 · 게시판 · 계정 · 관리(ADMIN) · 로그아웃.
// AuthProvider 안팎 모두 사용(useAuthOptional). 라벨은 기존 상수 그대로. 현재 구역은 페이지가 prop 으로 준다
// (next/navigation 훅에 기대지 않아 서버 렌더와 테스트에서 결정적이다).
export default function Topbar({ current }: { readonly current?: TopbarSection } = {}) {
    // AuthProvider 밖(공개 게시판 껍데기 테스트 등)에서도 그려진다 — 세션이 없으면 로그인 링크만.
    const auth = useAuthOptional();
    const user = auth?.user ?? null;
    const logout = auth?.logout ?? (() => Promise.resolve());
    const on = (href: string) => current === href.slice(1);
    return (
        <header className="os-topbar lobby-topbar" aria-label="상단바">
            <div className="os-topbar__left">
                <Link href="/lobby" className="lobby-brand">
                    <Brand size="large" className="os-topbar__brand" />
                </Link>
                <nav className="os-topbar__nav" aria-label="게이트웨이">
                    <Link href="/lobby" className={`os-nav-item${on('/lobby') ? ' os-nav-item--on' : ''}`} aria-current={on('/lobby') ? 'page' : undefined}>
                        {LOBBY_LABELS.navLobby}
                    </Link>
                    <Link href="/board" className={`os-nav-item${on('/board') ? ' os-nav-item--on' : ''}`} aria-current={on('/board') ? 'page' : undefined}>
                        {LOBBY_LABELS.navBoard}
                    </Link>
                    <Link href="/account" className={`os-nav-item${on('/account') ? ' os-nav-item--on' : ''}`} aria-current={on('/account') ? 'page' : undefined}>
                        {LOBBY_LABELS.navAccount}
                    </Link>
                    {user?.role === 'ADMIN' && (
                        <Link href="/admin" className={`os-nav-item${on('/admin') ? ' os-nav-item--on' : ''}`} aria-current={on('/admin') ? 'page' : undefined}>
                            {LOBBY_LABELS.admin}
                        </Link>
                    )}
                </nav>
            </div>
            <div className="os-topbar__right lobby-user">
                {user && <span className="os-topbar__user">{user.nickname || user.username}</span>}
                <button type="button" className="os-button os-button--ghost os-button--sm btn-ghost" onClick={() => void logout()}>
                    {AUTH_LABELS.logout}
                </button>
            </div>
        </header>
    );
}
