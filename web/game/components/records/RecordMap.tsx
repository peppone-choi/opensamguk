'use client';

import { useMemo } from 'react';
import { topdownSourceFor } from '@opensamguk/ui/map/topdown';
import { useCampaignWorldMap } from '@/lib/campaign-map';
import RecordTopdownMap from './RecordTopdownMap';
import styles from './records.module.css';

/**
 * 고른 기록의 현을 가운데 둔 지도(지도가 주인공 — 설계서 §1.2). 현이 없는 기록은 지도를 부르지 않는다.
 * 지도는 서버가 bakeId를 줄 때만 그린다. 없으면 「지도를 준비 중입니다」(D113, 옛 지도로 돌아가지 않는다).
 * 훅은 미리보기에서 멈춘다(지형 · 省 그림을 받지 않는다).
 */
export default function RecordMap({ cityId, label }: { readonly cityId: number; readonly label: string }) {
  const map = useCampaignWorldMap();
  const bakeId = map.kind === 'preview' ? map.preview.topdownBakeId : undefined;
  const topdown = useMemo(() => topdownSourceFor(bakeId), [bakeId]);
  if (map.kind === 'loading') return <div className={styles.map}><p className={styles.mapEmpty}>지도를 불러오는 중입니다.</p></div>;
  if (map.kind === 'error') return <div className={styles.map}><p className={styles.mapEmpty}>지도를 불러오지 못했습니다.</p></div>;
  if (map.kind === 'unsupported') return <div className={styles.map}><p className={styles.mapEmpty}>이 서버의 지도는 기록 화면에서 그릴 수 없습니다.</p></div>;
  if (!topdown) return <div className={styles.map}><p className={styles.mapEmpty} data-map-preparing>지도를 준비 중입니다.</p></div>;
  return <div className={styles.map}><RecordTopdownMap source={topdown} preview={map.preview} cityId={cityId} label={label} /></div>;
}
