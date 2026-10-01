'use client';

import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useGameSession } from '@/lib/campaign-session';
import { LOBBY_HREF } from '@/lib/gatewayLinks';
import styles from './EntryScreen.module.css';

/** Approved E01 board; missing policy, map and nation contracts remain visible. */
export default function EntryScreen() {
  const { frontInfo, gameDate } = useGameSession();
  const world = frontInfo?.global;
  return <section className={styles.screen} aria-label="게임 입구" data-testid="game-entry-screen">
    <aside className={styles.map} data-contract-id="K2">
      <StatusView kind="waiting" title="판도 지도는 서버 대기" body="지도와 세력별 현 정보를 준비하고 있습니다." />
    </aside>
    <div className={`os-panel ${styles.panel}`}>
      <h2 className="os-serif">이 서버에서 시작한다</h2>
      <div aria-label="서버 요약">
        <p>{world?.scenarioText || world?.scenario || '시나리오 정보는 서버 대기'}</p>
        <p>{gameDate}{world?.turnterm != null ? ` · 한 순 ${world.turnterm}분` : ''}</p>
        <p>사람 장수 상한은 서버 대기 · NPC {world?.npcCount ?? '서버 대기'} · 세력 {world?.nationCount ?? '서버 대기'}</p>
      </div>
      <div data-contract-id="K5-01 K5-02">
        <StatusView kind="waiting" title="장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중" />
        <div className={styles.paths}>
          <article><h3>내 장수를 만든다</h3><p>이름 · 본관 현 · 다섯 능력 · 주의 · 개성을 정합니다.</p>
            <CampaignLink slug="create" className="os-button os-button--ghost">생성 화면 보기</CampaignLink></article>
          <article><h3>역사 인물로 시작</h3><p>등장한 인물 중 비어 있는 한 명을 고릅니다.</p>
            <CampaignLink slug="create/historical" className="os-button os-button--ghost">역사 인물 화면 보기</CampaignLink></article>
        </div>
      </div>
      <div data-contract-id="K5-11"><StatusView kind="waiting" title="세력 목록은 서버 대기" body="깃발 · 세력 · 주공 · 현 수를 서버가 아직 주지 않습니다." /></div>
      <p>재야 → 출사 → 현령 · 부장 → 태수 · 군단장 → 봉신 주공 또는 독립 → 군주</p>
      <a href={LOBBY_HREF} className="os-button os-button--ghost">로비로</a>
    </div>
  </section>;
}
