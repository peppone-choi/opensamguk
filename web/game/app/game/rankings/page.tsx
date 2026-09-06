'use client';
// 기록 허브(12 아트보드) — 랭킹 하위 화면 7종으로 가는 타일. 라벨·상대 href 는 그대로(경로 서버 id 보존).
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import RecordsTabs from '../../../components/records/RecordsTabs';

const RANKING_PAGES = [
  { href: 'rankings/best-generals', label: '명장 순위', desc: '통솔·무력·지력 종합 평가', icon: '⚔️' },
  { href: 'rankings/emperor', label: '황제 정보', desc: '천하 통일 황제 기록', icon: '👑' },
  { href: 'rankings/generals', label: '장수 일람', desc: '모든 장수 능력치 순위', icon: '📜' },
  { href: 'rankings/kingdoms', label: '세력 순위', desc: '국가별 병력·자원·영토', icon: '🏛️' },
  { href: 'rankings/npcs', label: 'NPC 일람', desc: '비플레이어 장수 목록', icon: '🤖' },
  { href: 'rankings/hall-of-fame', label: '명예의 전당', desc: '역대 최고 기록 보관', icon: '🏆' },
  { href: 'rankings/traffic', label: '접속 통계', desc: '서버 접속자 현황', icon: '📊' },
];

export default function RankingsLobbyPage() {
  return (
    <Shell>
      <PageHead title="경기장 대갑실" tabs={<RecordsTabs />} />
      <div className="records-hub">
        {RANKING_PAGES.map((p) => (
          <a key={p.href} href={p.href} className="os-panel os-panel--static records-hub__item">
            <span className="records-hub__icon" aria-hidden="true">{p.icon}</span>
            <span>
              <span className="records-hub__label">{p.label}</span>
              <span className="records-hub__desc" style={{ display: 'block' }}>{p.desc}</span>
            </span>
          </a>
        ))}
      </div>
    </Shell>
  );
}
