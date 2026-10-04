'use client';

import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import styles from './EntryScreen.module.css';

/** No generation request or LORD policy is inferred before T3 delivery. */
export default function CreationWaiting({ historical = false }: { readonly historical?: boolean }) {
  return <section className={styles.panel} data-testid={historical ? 'historical-waiting' : 'creation-waiting'}>
    <h2 className="os-serif">{historical ? '역사 인물로 시작' : '내 장수를 만든다'}</h2>
    <div data-contract-id={historical ? 'K5-01 K5-03' : 'K5-01 K5-02'}>
      <StatusView kind="waiting" title="장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중"
        body={historical ? '역사 인물 후보와 선택 정책을 서버가 아직 주지 않습니다.' : '이름 규칙 · 본관 현 · 생성 정책을 서버가 아직 주지 않습니다.'} />
    </div>
    <CampaignLink slug="" className="os-button os-button--ghost">입구로</CampaignLink>
  </section>;
}
