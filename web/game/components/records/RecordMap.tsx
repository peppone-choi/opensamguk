'use client';

import { useMemo } from 'react';
import { WorldMapCanvas } from '@opensamguk/ui';
import { topdownScreensEnabled, topdownSourceFor } from '@opensamguk/ui/map/topdown';
import { CAMPAIGN_MAP_CODE, CAMPAIGN_PROVINCES_URL, useCampaignWorldMap } from '@/lib/campaign-map';
import RecordTopdownMap from './RecordTopdownMap';
import styles from './records.module.css';

/**
 * 고른 기록의 현을 가운데 둔 지도(지도가 주인공 — 설계서 §1.2). 현이 없는 기록은 지도를 부르지 않는다.
 * 초점은 지도가 처음 그릴 때만 맞으므로 현이 바뀌면 캔버스를 새로 세운다(지도 자료는 위 훅이 그대로 쥔다).
 * 새 지도는 교체 스위치가 켜져 있고 서버가 bakeId를 줄 때만(둘 중 하나라도 없으면 옛 지도 그대로).
 */
export default function RecordMap({ cityId, label }: { readonly cityId: number; readonly label: string }) {
  const map = useCampaignWorldMap();
  const bakeId = map.kind === 'ready' ? map.preview.topdownBakeId : undefined;
  const topdown = useMemo(() => (topdownScreensEnabled() ? topdownSourceFor(bakeId) : null), [bakeId]);
  if (map.kind === 'loading') return <div className={styles.map}><p className={styles.mapEmpty}>지도를 불러오는 중입니다.</p></div>;
  if (map.kind === 'error') return <div className={styles.map}><p className={styles.mapEmpty}>지도를 불러오지 못했습니다.</p></div>;
  if (map.kind === 'unsupported') return <div className={styles.map}><p className={styles.mapEmpty}>이 서버의 지도는 기록 화면에서 그릴 수 없습니다.</p></div>;
  if (topdown) {
    return <div className={styles.map}><RecordTopdownMap source={topdown} preview={map.preview} cityId={cityId} label={label} /></div>;
  }
  return (
    <div className={styles.map}>
      <WorldMapCanvas key={cityId} mapCode={CAMPAIGN_MAP_CODE} tiles={map.tiles} tilesSha256={map.tilesSha256}
        provinceMap={map.provinceMap ?? undefined} provinceUrl={map.provinceMap ? undefined : CAMPAIGN_PROVINCES_URL}
        cities={map.cities} administrativeOwnership={map.administrativeOwnership} sourceSize={map.sourceSize}
        markerPositions={map.markerPositions} selectedCityId={cityId} cameraFocusCityId={cityId}
        initialFocus="current-city-close" politicalStyle="tint" ariaLabel={`${label} 일대 지도`}
        style={{ width: '100%', height: '100%' }} />
    </div>
  );
}
