'use client';

import { Brand, Icon, type IconName } from '@opensamguk/ui';
import Link from 'next/link';
import Shell from '../components/Shell';
import GameCard from '../components/GameCard';

const GAME_PAGES: readonly { href: string; label: string; desc: string; icon: IconName }[] = [
    { href: '/game/auction', label: '경매장', desc: '아이템 및 자원 경매 참여', icon: 'auction' },
    { href: '/game/betting', label: '베팅', desc: '국가 강약 예측 및 베팅', icon: 'dice' },
    { href: '/game/diplomacy', label: '외교', desc: '국가 간 외교 관계 및 제의', icon: 'diplomacy' },
    { href: '/game/mailbox', label: '메일함', desc: '외교 메시지 및 알림 확인', icon: 'mail' },
    { href: '/game/nation', label: '국가 정보', desc: '국가 상세 및 유산 버프', icon: 'hub-kingdoms' },
    { href: '/game/simulator', label: '전투 시뮬레이터', desc: '장수 간 전투 시뮬레이션', icon: 'hub-best-generals' },
    { href: '/game/tournament-admin', label: '토너먼트 관리', desc: '토너먼트 운영 및 대진 관리', icon: 'hub-hall-of-fame' },
    { href: '/game/admin1', label: '게임 관리', desc: '운영자 메시지와 턴 설정 확인', icon: 'tools' },
    { href: '/game/admin2', label: '회원 관리', desc: '장수 제재 상태와 예약 명령 확인', icon: 'members' },
    { href: '/game/admin5', label: '일제 정보', desc: '국가별 통계와 정렬 정보', icon: 'hub-traffic' },
    { href: '/game/admin7', label: '로그 정보', desc: '장수별 기록과 전투 로그', icon: 'dept-records' },
    { href: '/game/admin8', label: '외교 정보', desc: '국가 간 외교 관계 목록', icon: 'diplomacy' },
];

export default function Home() {
    return (
        <Shell>
            <div style={{ textAlign: 'center', marginBottom: 'var(--space-xl)' }}>
                <h1 style={{ display: 'flex', justifyContent: 'center', marginBottom: 'var(--space-sm)' }}>
                    <Brand size="large" />
                </h1>
                <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                    오픈삼국 — 메모리 중심 CQRS 재작성
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
