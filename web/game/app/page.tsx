'use client';

import { Brand, Icon, type IconName } from '@opensamguk/ui';
import Link from 'next/link';
import Shell from '../components/Shell';
import GameCard from '../components/GameCard';

const GAME_PAGES: readonly { href: string; label: string; desc: string; icon: IconName }[] = [
    { href: '/game/hwiha/war-room', label: '작전실', desc: '지도와 12순 행동 확인', icon: 'hub-kingdoms' },
    { href: '/game/hwiha/retinue', label: '휘하 편성', desc: '인물과 부곡 편성', icon: 'members' },
    { href: '/game/hwiha/court', label: '조정', desc: '관직·외교·천도', icon: 'diplomacy' },
    { href: '/game/mailbox', label: '메일함', desc: '외교 메시지 및 알림 확인', icon: 'mail' },
    { href: '/game/my-nation', label: '국가 정보', desc: '소속 국가와 도시 확인', icon: 'hub-kingdoms' },
    { href: '/game/rankings', label: '랭킹', desc: '장수와 국가 기록', icon: 'hub-hall-of-fame' },
];

export default function Home() {
    return (
        <Shell>
            <div style={{ textAlign: 'center', marginBottom: 'var(--space-xl)' }}>
                <h1 style={{ display: 'flex', justifyContent: 'center', marginBottom: 'var(--space-sm)' }}>
                    <Brand size="large" />
                </h1>
                <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                    오픈삼국 휘하
                </p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 'var(--space-md)' }}>
                {GAME_PAGES.map(page => (
                    <Link key={page.href} href={page.href} style={{ textDecoration: 'none' }}>
                        <GameCard className="lobby-card">
                            <div style={{ marginBottom: 'var(--space-sm)', color: 'var(--gold)' }}><Icon name={page.icon} size={32} /></div>
                            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-xs)', color: 'var(--gold)' }}>
                                {page.label}
                            </h2>
                            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>
                                {page.desc}
                            </p>
                        </GameCard>
                    </Link>
                ))}
            </div>
        </Shell>
    );
}
