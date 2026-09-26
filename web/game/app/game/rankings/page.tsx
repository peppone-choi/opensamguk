'use client';
// 기록 허브(12 아트보드) — 랭킹 하위 화면 3종으로 가는 타일. 삼모 전용 랭킹 4종(황제·NPC·명예의 전당·접속 통계)은 대체 없이 지웠다(ADR-LITE-049 2026-09-26). 라벨·상대 href 는 그대로(경로 서버 id 보존).
import { Icon, type IconName } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import RecordsTabs from '../../../components/records/RecordsTabs';

const RANKING_PAGES: readonly { href: string; label: string; desc: string; icon: IconName }[] = [
  { href: 'rankings/best-generals', label: '명장 순위', desc: '통솔·무력·지력 종합 평가', icon: 'hub-best-generals' },
  { href: 'rankings/generals', label: '장수 일람', desc: '모든 장수 능력치 순위', icon: 'hub-generals' },
  { href: 'rankings/kingdoms', label: '세력 순위', desc: '국가별 병력·자원·영토', icon: 'hub-kingdoms' },
];

export default function RankingsLobbyPage() {
  return (
    <Shell>
      <PageHead title="경기장 대갑실" tabs={<RecordsTabs />} />
      <div className="records-hub">
        {RANKING_PAGES.map((p) => (
          <a key={p.href} href={p.href} className="os-panel os-panel--static records-hub__item">
            <span className="records-hub__icon"><Icon name={p.icon} size={24} /></span>
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
