'use client';

import { useMemo, useState, type CSSProperties } from 'react';
import type { CommanderyVisibility } from '@opensamguk/ui';
import { Panel, useViewportClass } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import Toast from '@/components/Toast';
import MessagePanel from '@/components/game/MessagePanel';
import CountyPanel from '@/components/campaign/CountyPanel';
import GeneralRoster from '@/components/campaign/GeneralRoster';
import { LastTurnsDrawer } from '@/components/campaign/LastTurnsDrawer';
import StandingBar from '@/components/campaign/StandingBar';
import WarRoomMap from '@/components/campaign/WarRoomMap';
import CommandFlow from '@/components/command-flow/CommandFlow';
import { CommandFlowHost } from '@/components/command-flow/CommandFlowHost';
import { TurnSlots } from '@/components/turn-slots/TurnSlots';
import { useToast } from '@/hooks/useToast';
import { api } from '@/lib/api';
import { campaignHref } from '@/lib/campaign-screens';
import { useCampaignRead } from '@/lib/campaign-reads';
import { reserveScout } from '@/lib/campaign-scout';
import { useGameSession } from '@/lib/campaign-session';
import { useFlowQuery } from '@/lib/command-flow/use-flow-query';
import { useTurnSlots } from '@/lib/turn-slots';
import styles from './WarRoomPage.module.css';

/**
 * 작전실 — 시안 WarRoom(메인).
 *
 * 삼모의 「현황」 자리이지만 구조가 다르다. 삼모는 24칸 명령 큐에 명령을 쌓고 턴마다 순차 실행하는데,
 * 여기서는 **개인 턴 12순**이고 한 순에 직접 행동 하나다. 맡겨 둔 일(배치·발령·계책)은 개인 턴을
 * 쓰지 않고 스스로 굴러간다 — 「걸려 있는 것」.
 *
 * 지도는 휘하 규칙이 아닌 서버에서도 보인다(공개 지도 API). 나머지 패널은 장수가 있어야 뜻이 있다.
 */
export default function WarRoomPage() {
    const session = useGameSession();
    const { frontInfo, generalId, refresh, serverId } = session;
    const mobile = useViewportClass() === 'mobile';
    // 지난 순 서랍(P-W04)이 열리면 지도 보기 단추를 서랍 오른쪽으로(WarRoomTopdownMap --map-viewbar-left, K2 합의 10-01).
    const [drawerOpen, setDrawerOpen] = useState(false);
    const mapWrapStyle = drawerOpen && !mobile ? ({ '--map-viewbar-left': '380px' } as CSSProperties) : undefined;
    const { toasts, show, remove } = useToast();
    const [refreshKey, setRefreshKey] = useState(0);
    // 명령 흐름(P-W02, K6) — 주소 ?do · slot · target 이 있으면 12순 열 자리를 흐름이 차지한다(설계서 §2.1).
    const flow = useFlowQuery();
    const turnSlots = useTurnSlots(generalId, refreshKey);
    const bump = () => {
        setRefreshKey((k) => k + 1);
        refresh();
    };

    // 시야·군단·첩보 — 서버 투영이 정한다. 조회 실패 시 레이어를 비운다.
    const vision = useCampaignRead((id, signal) => api.campaignVisibility(id, signal), [refreshKey]);
    const corps = useCampaignRead((id, signal) => api.campaignCorps(id, signal), [refreshKey]);
    const sieges = useCampaignRead((id, signal) => api.campaignSieges(id, signal), [refreshKey]);
    const works = useCampaignRead((id, signal) => api.campaignWorks(id, signal), [refreshKey]);
    const scout = useCampaignRead((id, signal) => api.campaignScoutOptions(id, signal), [refreshKey]);
    const visibility = useMemo(() => {
        const list = vision.data?.status === 'READY' ? vision.data.commanderies : undefined;
        return list ? new Map<number, CommanderyVisibility>(list.map((c) => [c.no, c.tier])) : null;
    }, [vision.data]);
    const intelAge = useMemo(
        () => new Map((vision.data?.commanderies ?? []).filter((c) => c.ageTurns != null).map((c) => [c.no, c.ageTurns!])),
        [vision.data],
    );
    const scoutable = useMemo(
        () => new Set((scout.data?.options ?? []).filter((o) => o.available).map((o) => o.no)),
        [scout.data],
    );
    const [scoutPending, setScoutPending] = useState(false);
    // 첩보는 직접 행동 — 명령 목록 12순의 첫 빈 순에 예약한다.
    const sendScout = async (commanderyNo: number) => {
        const option = scout.data?.options?.find((o) => o.no === commanderyNo);
        if (generalId == null || !option) return;
        setScoutPending(true);
        try {
            const result = await reserveScout(generalId, option);
            show(result.message, result.ok ? 'success' : 'error');
            if (result.ok) bump();
        } finally {
            setScoutPending(false);
        }
    };

    return (
        <GameShell title="작전실" tab={null} showBack={false} requiresHwiha={false} bleed>
            <div
                className={styles.layout}
                data-flow-open={flow.query.open || undefined}
                data-testid="war-room-layout"
            >
                <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
                    {/* 안개는 서버 시야 투영(군국 단위)만 따른다. 지난 순 서랍(P-W04)은 지도를 밀지 않고 덮는다. */}
                    <div className={styles.mapWrap} style={mapWrapStyle}>
                    {frontInfo && generalId != null ? (
                        <LastTurnsDrawer mobile={mobile} onOpenChange={setDrawerOpen} hrefs={{
                            court: campaignHref('court?tab=orders', serverId), yuedan: campaignHref('retinue/yuedan', serverId), records: campaignHref('records', serverId),
                        }} />
                    ) : null}
                    <WarRoomMap
                        refreshKey={refreshKey}
                        homeCityId={frontInfo?.city?.id ?? null}
                        visibility={visibility}
                        intelAge={intelAge}
                        corps={corps.data?.corps}
                        works={works.data}
                        sieges={sieges.data}
                        scoutable={scoutable}
                        onScout={generalId != null ? (no) => void sendScout(no) : undefined}
                        scoutPending={scoutPending}
                    />
                    </div>
                    {vision.error || vision.data?.status === 'WRONG_RULE_PROFILE' ? <p role="status">시야를 불러오지 못해 안개 레이어를 비웠습니다.</p> : null}
                    {corps.error || corps.data?.status === 'WRONG_RULE_PROFILE' ? <p role="status">군단을 불러오지 못해 군단 레이어를 비웠습니다.</p> : null}
                    <p style={{ margin: 0, color: 'var(--muted)', fontSize: 12 }}>
                        요격은 시야·거리 조건을 만족한 적의 진입에 조우로 대응합니다. 회피는 조건을 만족하면 이동 가능한 인접 아군 지역으로 후퇴합니다.
                    </p>
                    {frontInfo && generalId != null ? (
                        <>
                            <CountyPanel city={frontInfo.city} />
                            <StandingBar />
                            {/* 옛 「지난 순」 패널 · world_log 3탭(MainRecordZone)은 지도 위 지난 순 서랍 한 벌로 합쳤다(P-W04). */}
                            <MessagePanel
                                generalId={generalId}
                                nationId={frontInfo.general.nationId}
                                refreshKey={refreshKey}
                                onToast={show}
                            />
                        </>
                    ) : null}
                </div>

                {generalId != null && frontInfo && flow.query.open ? (
                    <CommandFlowHost>
                        <CommandFlow
                            generalId={generalId}
                            generalName={frontInfo.general.name}
                            initialInputId={flow.query.inputId}
                            initialSlot={flow.query.slot}
                            initialTarget={flow.query.target}
                            refreshKey={refreshKey}
                            onClose={flow.closeFlow}
                            onLocationChange={flow.syncFlow}
                            onReserved={bump}
                        />
                    </CommandFlowHost>
                ) : generalId != null && frontInfo ? (
                    <div style={{ display: 'grid', gap: 12, alignContent: 'start' }}>
                    <GeneralRoster />
                    <Panel style={{ padding: 0 }}>
                        <h2 style={{ margin: 0, padding: '8px 12px', fontSize: 14, borderBottom: '1px solid var(--line)' }}>명령 목록 12순 — 직접 행동 · 한 순에 하나</h2>
                        <TurnSlots
                            mode="column"
                            load={turnSlots.load}
                            onSelect={(turnIdx) => flow.openFlow({ slot: turnIdx })}
                            onRetry={turnSlots.reload}
                        />
                    </Panel>
                    </div>
                ) : (
                    <Panel style={{ padding: 12 }}>
                        <p style={{ margin: 0, color: 'var(--muted)', fontSize: 13 }}>
                            {session.loading ? '장수 정보를 불러오는 중입니다.' : '이 서버에 장수가 없습니다.'}
                        </p>
                    </Panel>
                )}
            </div>
            <Toast toasts={toasts} onRemove={remove} />
        </GameShell>
    );
}
