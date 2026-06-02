'use client';

import Link from 'next/link';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';

const RANKING_PAGES = [
  { href: '/game/rankings/best-generals', label: '명장 순위', desc: '통솔·묠력·지력 종합 평가', icon: '⚔️' },
  { href: '/game/rankings/emperor', label: '황제 정보', desc: '천하 통일 황제 기록', icon: '👑' },
  { href: '/game/rankings/generals', label: '장수 일람', desc: '모든 장수 능력치 순위', icon: '📜' },
  { href: '/game/rankings/kingdoms', label: '세력 순위', desc: '국가별 병력·자원·영토', icon: '🏛️' },
  { href: '/game/rankings/npcs', label: 'NPC 일람', desc: '비플레이어 장수 목록', icon: '🤖' },
  { href: '/game/rankings/hall-of-fame', label: '명예의 전당', desc: '역대 최고 기록 보관', icon: '🏆' },
  { href: '/game/rankings/traffic', label: '접속 통계', desc: '서버 접속자 현황', icon: '📊' },
];

export default function RankingsLobbyPage() {
  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>
        경기장 대갑실
      </h1>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
          gap: 'var(--space-md)',
        }}
      >
        {RANKING_PAGES.map((p) => (
          <Link key={p.href} href={p.href} style={{ textDecoration: 'none' }}>
            <GameCard
              className="ranking-lobby-card"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--space-md)',
                cursor: 'pointer',
              }}
            >
              <span style={{ fontSize: '2rem' }}>{p.icon}</span>
              <div>
                <div
                  style={{
                    fontSize: 'var(--text-lg)',
                    fontWeight: 600,
                    color: 'var(--text-primary)',
                  }}
                >
                  {p.label}
                </div>
                <div
                  style={{
                    fontSize: 'var(--text-sm)',
                    color: 'var(--text-secondary)',
                    marginTop: 'var(--space-xs)',
                  }}
                >
                  {p.desc}
                </div>
              </div>
            </GameCard>
          </Link>
        ))}
      </div>
    </Shell>
  );
}
