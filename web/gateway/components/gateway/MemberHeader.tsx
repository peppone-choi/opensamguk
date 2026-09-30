'use client';

import { useEffect, useId, useRef, useState } from 'react';
import Link from 'next/link';
import { Brand, Chip } from '@opensamguk/ui';
import { useAuthOptional } from '@/lib/auth-context';

export type MemberNav = 'lobby' | 'board' | 'account' | 'admin';

const NAV: readonly { readonly key: MemberNav; readonly label: string; readonly href: string; readonly admin?: boolean }[] = [
    { key: 'lobby', label: '로비', href: '/lobby' },
    { key: 'board', label: '커뮤니티', href: '/board' },
    { key: 'account', label: '계정', href: '/account' },
    { key: 'admin', label: '관리', href: '/admin', admin: true },
];

/**
 * 게이트웨이 셸 머리줄 — 로그인 뒤(설계서 §1.1 · §2.0 T1–T7). 데스크톱 48: 로고 · 「공개 알파」 · 메뉴 · 별명 · 로그아웃.
 * 모바일 56: 로고 + 「메뉴」(44) → 하단 시트(로비 · 커뮤니티 · 계정 · 관리 · 로그아웃). 관리는 운영자만 보인다.
 * 로비 안에 두 벌씩 있던 계정 · 커뮤니티 · 관리 · 로그아웃을 여기 한 곳에 모은다(LB32–LB36).
 */
export default function MemberHeader({ current }: { readonly current: MemberNav }) {
    const auth = useAuthOptional();
    const user = auth?.user ?? null;
    const [open, setOpen] = useState(false);
    const sheetId = useId();
    const opener = useRef<HTMLButtonElement>(null);
    const firstLink = useRef<HTMLAnchorElement>(null);
    const items = NAV.filter((item) => !item.admin || user?.role === 'ADMIN');
    const name = user ? (user.nickname || user.username) : '';

    useEffect(() => {
        if (!open) return;
        firstLink.current?.focus();
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                setOpen(false);
                opener.current?.focus();
            }
        };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [open]);

    const logout = () => { void auth?.logout(); };

    return (
        <header className="gw31-head gw31-member" aria-label="상단바">
            <div className="gw31-head__left">
                <Link href="/lobby" className="gw31-head__brand" aria-label="오픈삼국 — 로비로">
                    <Brand size="large" />
                </Link>
                <Chip tone="bronze">공개 알파</Chip>
                <nav className="gw31-member__nav" aria-label="게이트웨이 메뉴">
                    {items.map((item) => (
                        <Link key={item.key} href={item.href} className="gw31-member__link" aria-current={item.key === current ? 'page' : undefined}>
                            {item.label}
                        </Link>
                    ))}
                </nav>
            </div>
            <div className="gw31-head__right">
                {name && <span className="gw31-member__name">{name}</span>}
                {auth && <button type="button" className="os-button os-button--ghost gw31-btn gw31-member__logout" onClick={logout}>로그아웃</button>}
                <button
                    ref={opener}
                    type="button"
                    className="os-button os-button--ghost gw31-btn gw31-member__menu"
                    aria-haspopup="dialog"
                    aria-expanded={open}
                    aria-controls={sheetId}
                    onClick={() => setOpen(true)}
                >
                    메뉴
                </button>
            </div>
            {open && (
                <>
                    <div className="gw31-scrim" aria-hidden="true" onClick={() => setOpen(false)} />
                    <section id={sheetId} className="gw31-sheet" role="dialog" aria-modal="true" aria-label="메뉴">
                        <div className="gw31-sheet__head">
                            <span className="os-serif gw31-sheet__title">{name || '메뉴'}</span>
                            <button type="button" className="os-button os-button--ghost gw31-btn" onClick={() => { setOpen(false); opener.current?.focus(); }}>닫기</button>
                        </div>
                        <nav aria-label="게이트웨이 메뉴(시트)">
                            {items.map((item, index) => (
                                <Link
                                    key={item.key}
                                    ref={index === 0 ? firstLink : undefined}
                                    href={item.href}
                                    className="gw31-sheet__link"
                                    aria-current={item.key === current ? 'page' : undefined}
                                    onClick={() => setOpen(false)}
                                >
                                    {item.label}
                                </Link>
                            ))}
                        </nav>
                        {auth && <button type="button" className="os-button os-button--ghost gw31-btn gw31-sheet__logout" onClick={logout}>로그아웃</button>}
                    </section>
                </>
            )}
        </header>
    );
}
