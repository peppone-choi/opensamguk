'use client';

import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useCreationOptions } from '@/hooks/useCreationOptions';
import { reasonText, seatsLine } from '@/lib/create-view';
import { useGameSession } from '@/lib/campaign-session';
import { LOBBY_HREF } from '@/lib/gatewayLinks';
import styles from './EntryScreen.module.css';

/** Approved E01 board; creation availability comes from the same options as the editor. */
export default function EntryScreen() {
  const { frontInfo, gameDate } = useGameSession();
  const world = frontInfo?.global;
  const { state, reload } = useCreationOptions();
  const options = state.kind === 'ready' ? state.data : null;
  const cap = options?.playerCap;
  const full = cap != null && cap.used >= cap.max;
  const closed = options ? reasonText(options.policy.reason) : null;
  return <section className={styles.screen} aria-label="게임 입구" data-testid="game-entry-screen">
    <aside className={styles.map} data-contract-id="K2">
      <StatusView kind="waiting" title="입구 지도는 준비 중입니다" body="본관 현은 생성 화면의 후보 목록에서 확인할 수 있습니다." />
    </aside>
    <div className={`os-panel ${styles.panel}`}>
      <h2 className="os-serif">이 서버에서 시작한다</h2>
      <div aria-label="서버 요약">
        <p>{world?.scenarioText || world?.scenario || '시나리오 정보는 서버 대기'}</p>
        <p>{gameDate}{world?.turnterm != null ? ` · 한 순 ${world.turnterm}분` : ''}</p>
        <p>{cap ? seatsLine(cap) : '사람 장수 정원을 확인하지 못했습니다'} · NPC {world?.npcCount ?? '서버 대기'} · 세력 {world?.nationCount ?? '서버 대기'}</p>
      </div>
      <div data-contract-id="K5-01 K5-02">
        {state.kind === 'loading' ? <p>생성 조건을 확인하고 있습니다.</p> : null}
        {state.kind === 'waiting' ? <StatusView kind="waiting"
          title={state.message ?? (state.code ? reasonText(state.code) : '생성 조건을 아직 확인할 수 없습니다.')}
          body="생성 화면에서 현재 조건을 다시 확인해 주세요." /> : null}
        {state.kind === 'error' ? <StatusView kind="error" title="생성 조건을 불러오지 못했습니다"
          body={state.error.message} onRetry={reload} /> : null}
        {full ? <StatusView kind="waiting" title="사람 장수 자리가 다 찼습니다."
          body="자리가 생긴 뒤 생성 화면에서 다시 확인해 주세요." /> : null}
        <div className={styles.paths}>
          <article><h3>내 장수를 만든다</h3><p>이름 · 본관 현 · 다섯 능력 · 주의 · 개성을 정합니다.</p>
            {options && !full ? <p>{options.policy.customAllowed ? '내 장수를 만들 수 있습니다.' : closed}</p> : null}
            <CampaignLink slug="create" className="os-button os-button--ghost">생성 화면 보기</CampaignLink></article>
          <article><h3>역사 인물로 시작</h3><p>등장한 인물 중 비어 있는 한 명을 고릅니다.</p>
            {options && !full ? <p>{options.policy.historicalAllowed ? '역사 인물로 시작할 수 있습니다.' : closed}</p> : null}
            <CampaignLink slug="create/historical" className="os-button os-button--ghost">역사 인물 화면 보기</CampaignLink></article>
        </div>
      </div>
      <div data-contract-id="K5-11"><StatusView kind="waiting" title="입구 세력 목록은 준비 중입니다" body="섬길 세력과 장수의 후보는 장수를 만든 뒤 출사 화면에서 확인합니다." /></div>
      <p>재야 → 출사 → 현령 · 부장 → 태수 · 군단장 → 봉신 주공 또는 독립 → 군주</p>
      <a href={LOBBY_HREF} className="os-button os-button--ghost">로비로</a>
    </div>
  </section>;
}
