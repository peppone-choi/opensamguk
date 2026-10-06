'use client';

import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import styles from './EntryScreen.module.css';

/**
 * 장수 만들기 서버를 기다리는 상태 — 경로가 아직 없거나(404) 정책이 닫혔을 때(503). 서버가 준 문장이 있으면 그대로 보인다.
 * 요청 · LORD 정책은 지어내지 않는다(T3 계약 전).
 * `serverWait` 는 서버가 계약 본문 없이 답했을 때(경로 없음)만 준다 — 기다리는 계약판 행(K10 서버 대기 표지).
 * 서버가 문장을 준 정책 닫힘은 서버가 답한 것이라 표지를 달지 않는다.
 */
export default function CreationWaiting({ historical = false, message = null, serverWait = null }: {
  readonly historical?: boolean; readonly message?: string | null; readonly serverWait?: string | null;
}) {
  return <section className={styles.panel} data-testid={historical ? 'historical-waiting' : 'creation-waiting'}>
    <h2 className="os-serif">{historical ? '역사 인물로 시작' : '내 장수를 만든다'}</h2>
    <div data-contract-id={historical ? 'K5-01 K5-03' : 'K5-01 K5-02'} data-server-wait={serverWait ?? undefined}>
      <StatusView kind="waiting" title="장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중"
        body={message ?? (historical ? '역사 인물 후보와 선택 정책을 서버가 아직 주지 않습니다.' : '이름 규칙 · 본관 현 · 생성 정책을 서버가 아직 주지 않습니다.')} />
    </div>
    <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
  </section>;
}
