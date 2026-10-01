'use client';

import type { TurnBand } from '@/lib/turnLoop';
import styles from './shell.module.css';

/**
 * 알림 띠(P-W05) — 머리줄 바로 아래 한 줄, 한 번에 하나. 턴 멈춤만 role=alert, 나머지는 status(K10).
 * 「공지 보기」처럼 갈 곳이 아직 없는 조작은 두지 않는다(지어내지 않는다).
 */
export default function NoticeBand({ band, onRecheck }: { readonly band: TurnBand | null; readonly onRecheck: () => void }) {
  if (!band) return null;
  return (
    <div className={`${styles.band} ${styles[`band_${band.kind}`] ?? ''}`} role={band.role} data-band={band.kind}>
      <span className={styles.bandText}><b>{band.head}</b> — {band.body}</span>
      {band.action === 'recheck' ? (
        <button type="button" className={`os-button os-button--ghost ${styles.bandAction}`} onClick={onRecheck}>다시 확인</button>
      ) : null}
    </div>
  );
}
