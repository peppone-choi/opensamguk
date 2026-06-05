'use client';

import Link from 'next/link';
import Shell from '../components/Shell';
import GameCard from '../components/GameCard';

const GAME_PAGES = [
    { href: '/game/auction', label: '경매장', desc: '아이템 및 자원 경매 참여', icon: '⚖️' },
    { href: '/game/betting', label: '베팅', desc: '국가 강약 예측 및 베팅', icon: '🎲' },
    { href: '/game/diplomacy', label: '외교', desc: '국가 간 외교 관계 및 제의', icon: '🤝' },
    { href: '/game/mailbox', label: '메일함', desc: '외교 메시지 및 알림 확인', icon: '✉️' },
    { href: '/game/nation', label: '국가 정보', desc: '국가 상세 및 유산 버프', icon: '🏛️' },
    { href: '/game/simulator', label: '전투 시뮬레이터', desc: '장수 간 전투 시뮬레이션', icon: '⚔️' },
    { href: '/game/tournament-admin', label: '토너먼트 관리', desc: '토너먼트 운영 및 대진 관리', icon: '🏆' },
];

export default function Home() {
    return (
        <Shell>
            <div style={{ textAlign: 'center', marginBottom: 'var(--space-xl)' }}>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-sm)' }}>
                    opensamguk
                </h1>
                <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                    오픈삼국 — 메모리 중심 CQRS 재작성
                </p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 'var(--space-md)' }}>
                {GAME_PAGES.map(page => (
                    <Link key={page.href} href={page.href} style={{ textDecoration: 'none' }}>
                        <GameCard className="lobby-card">
                            <div style={{ fontSize: '2rem', marginBottom: 'var(--space-sm)' }}>{page.icon}</div>
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
