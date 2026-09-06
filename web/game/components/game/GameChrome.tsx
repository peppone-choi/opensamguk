'use client';

// GameChrome — 작전실(03 아트보드, ADR-LITE-049). 지도(중앙)와 명령 목록(우측 12순)은 메인에 고정한다.
// 좌: 지난 순(장수 동향·개인 기록·중원 정세) / 중앙: 지도 + 현재 조작 대상(도시·국가·장수 카드, 국가 패널 하단 3행) /
// 우: 명령 목록 / 하단: 메시지 3탭. 20버튼·전역 메뉴는 쉘의 부서 나브(DeptNav)가 맡는다 — 라벨·게이팅 그대로.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { SectionHeader } from '@opensamguk/ui';
import { useFrontInfo } from '@/hooks/useFrontInfo';
import { useToast } from '@/hooks/useToast';
import GameInfo from './GameInfo';
import MainStatusPanel from './MainStatusPanel';
import MainRecordZone from './MainRecordZone';
import MapViewer from './MapViewer';
import PartialReservedCommand from './PartialReservedCommand';
import GeneralBasicCard from './GeneralBasicCard';
import NationBasicCard from './NationBasicCard';
import CityBasicCard from './CityBasicCard';
import CharacterClaim from './CharacterClaim';
import MessagePanel from './MessagePanel';
import RetinueSlot from './RetinueSlot';
import Toast from '../Toast';
import { resolveServerGamePath } from '@/lib/serverGameUrl';
import type { FrontInfoResponse } from '@/lib/types';

type GameChromeChildren = React.ReactNode | ((frontInfo: FrontInfoResponse) => React.ReactNode);
type GameChromeEntryMode = 'possession';

export default function GameChrome({ children, entryMode }: { children?: GameChromeChildren; entryMode?: GameChromeEntryMode }) {
  const router = useRouter();
  const { frontInfo, constData, loading, error, refresh, refreshKey } = useFrontInfo();
  const { toasts, show, remove } = useToast();
  const [possessionClaimed, setPossessionClaimed] = useState(false);
  // 순 전환 연출(Task 1.5): refreshKey 가 바뀌면 300ms 동안 .is-turning 을 켠다(reduced-motion 이면 --motion-turn 0).
  const [turning, setTurning] = useState(false);
  const lastRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return; // 첫 마운트는 연출하지 않는다
    lastRefreshKey.current = refreshKey;
    setTurning(true);
    const t = window.setTimeout(() => setTurning(false), 300);
    return () => window.clearTimeout(t);
  }, [refreshKey]);

  const hasGeneral = frontInfo?.general.hasGeneral ?? null;
  const joinHref = useMemo(() => {
    const serverId = frontInfo?.global.serverId;
    return serverId ? resolveServerGamePath(undefined, serverId, '/game', 'join') : '/game/join';
  }, [frontInfo?.global.serverId]);
  const gameHref = useMemo(() => {
    const serverId = frontInfo?.global.serverId;
    return serverId ? resolveServerGamePath(undefined, serverId, '/game') : '/game';
  }, [frontInfo?.global.serverId]);
  const myHref = useMemo(() => {
    const serverId = frontInfo?.global.serverId;
    return serverId ? resolveServerGamePath(undefined, serverId, '/game', 'my') : '/game/my';
  }, [frontInfo?.global.serverId]);
  const onPossessionClaimed = useCallback(() => {
    setPossessionClaimed(true);
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (!loading && possessionClaimed && hasGeneral === true) {
      router.replace(gameHref);
    }
  }, [gameHref, hasGeneral, loading, possessionClaimed, router]);

  useEffect(() => {
    if (!loading && hasGeneral === false && entryMode !== 'possession') {
      router.replace(joinHref);
    }
  }, [entryMode, hasGeneral, joinHref, loading, router]);

  // asyncReady gate (spec §1.1): suppress everything until the first front-info + const resolve.
  if (loading) {
    return (
      <div className="center-screen">
        <div className="spinner" />
        <p className="text-muted" style={{ marginTop: '1rem' }}>
          서버 갱신 중입니다.
        </p>
      </div>
    );
  }

  if (error || !frontInfo) {
    return (
      <div className="error-state" role="alert">
        <p>{error ?? '서버 정보를 불러올 수 없습니다.'}</p>
        <button type="button" className="os-button os-button--primary" onClick={refresh}>다시 시도</button>
      </div>
    );
  }

  if (!frontInfo.general.hasGeneral) {
    if (entryMode === 'possession') {
      return <CharacterClaim global={frontInfo.global} onClaimed={onPossessionClaimed} />;
    }

    return (
      <div className="center-screen">
        <div className="spinner" />
        <p className="text-muted" style={{ marginTop: '1rem' }}>
          장수 생성 화면으로 이동 중입니다.
        </p>
      </div>
    );
  }

  const general = frontInfo.general;
  const nation = frontInfo.nation;
  const city = frontInfo.city;
  // The player's OWN general id (front-info) — threaded into the command modal + reserved panel + messages.
  const generalId = general.generalId;

  return (
    <div className={`game-chrome war-room${turning ? ' is-turning' : ''}`}>
      {/* GameInfo 13셀 — 서버 정보 스트립 */}
      <GameInfo global={frontInfo.global} constData={constData} />

      <div className="ingame-board">
        {/* 좌: 지난 순 */}
        <aside className="ib-records os-panel os-panel--static" aria-label="지난 순">
          <SectionHeader title="지난 순" tone="info" sub={`${frontInfo.global.year}年 ${frontInfo.global.month}月${frontInfo.global.turnPhaseText ? ` ${frontInfo.global.turnPhaseText}` : ''}`} />
          <MainRecordZone recentRecord={frontInfo.recentRecord} />
        </aside>

        {/* 중앙: 지도 */}
        <div className="ib-map os-panel os-panel--static os-frame--bronze">
          <MapViewer live showMe={1} refreshKey={refreshKey} currentCityId={city?.id ?? null} gameConst={constData?.gameConst} initialFocus="current-city-close" />
        </div>

        {/* 우: 명령 목록 12순 */}
        <div className="ib-reserved">
          {generalId != null && (
            <PartialReservedCommand
              generalId={generalId}
              nationId={general.nationId}
              maxTurn={constData?.maxTurn}
              refreshKey={refreshKey}
              onReserved={refresh}
              onToast={show}
              hero={{ picture: general.picture, imageServer: general.imageServer, name: general.name, nationColor: nation?.color ?? null }}
            />
          )}
        </div>

        {/* 중앙 하단: 현재 조작 대상 — 도시 · 국가(+접속중인 국가·접속자·국가방침) · 장수 */}
        <section className="ib-subject-panel" aria-label="현재 조작 대상">
          <div className="subject-target-bar">
            <div className="subject-target-title">조작 대상</div>
            <div className="subject-target-current">
              <span>본인</span>
              <span>{general.name ?? '장수'}</span>
              <span>{nation?.name ?? '재야'}</span>
              <span>{city?.name ?? '소재 없음'}</span>
            </div>
            {/* D3-17 휘하 슬롯(Phase 4X-A): 가신·부곡 수 배지 + 내 정보 #retinue 링크. 없으면 점선 + 사유 — 숨기지 않는다. */}
            <RetinueSlot generalId={generalId ?? null} href={myHref} />
          </div>
          <div className="subject-secondary-grid">
            <CityBasicCard city={city} />
            <div className="subject-nation">
              <NationBasicCard nation={nation} />
              <MainStatusPanel frontInfo={frontInfo} />
            </div>
          </div>
          <GeneralBasicCard general={general} nation={nation} />
        </section>

        {/* 하단: 메시지 3탭 */}
        <div className="ib-messages">
          {generalId != null && <MessagePanel generalId={generalId} nationId={general.nationId} refreshKey={refreshKey} onToast={show} />}
        </div>
      </div>

      <div className="main-page-content">{typeof children === 'function' ? children(frontInfo) : children}</div>

      <Toast toasts={toasts} onRemove={remove} />
    </div>
  );
}
