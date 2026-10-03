'use client';

import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useSearchParams } from 'next/navigation';
import type { CommanderyVisibility } from '@opensamguk/ui';
import { StatusView, useViewportClass } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import Toast from '@/components/Toast';
import { HereCard } from '@/components/campaign/HereCard';
import { DRAWER_HANDLE_WIDTH, DRAWER_WIDTH, LastTurnsDrawer } from '@/components/campaign/LastTurnsDrawer';
import WarRoomMap from '@/components/campaign/WarRoomMap';
import { WarRoomTurnsColumn, WarRoomTurnsPeek } from '@/components/campaign/WarRoomTurns';
import CommandFlow from '@/components/command-flow/CommandFlow';
import { CommandFlowHost } from '@/components/command-flow/CommandFlowHost';
import { useToast } from '@/hooks/useToast';
import { api } from '@/lib/api';
import { campaignHref } from '@/lib/campaign-screens';
import { useCampaignRead } from '@/lib/campaign-reads';
import { reserveScout } from '@/lib/campaign-scout';
import { useGameSession } from '@/lib/campaign-session';
import { useFlowQuery } from '@/lib/command-flow/use-flow-query';
import { useTurnSlots } from '@/lib/turn-slots';
import { parseWarRoomMapView } from '@/lib/war-room-map-view';
import styles from './WarRoomPage.module.css';

/** 모바일 12순 엿보기 시트 높이(보드 V31K4MWarRoom peek 124) — 그 위에 선택 알약(48)이 선다. 지도 보기 단추 · 내 위치 화살표가 이 위로 비킨다. */
const PEEK_HEIGHT = 124;
const PILL_ROW = 56;

/**
 * 작전실 틀 높이 — 셸은 높이를 묶지 않는다(틀은 min-height 100dvh, 모바일은 문서가 스크롤되고 하단 탭이 sticky).
 * 그래서 지도가 남은 화면을 채우도록 틀의 문서 위치를 재어 `100dvh - 위치`(모바일은 하단 탭 --bottom-nav-inset 도 뺀다)로 둔다.
 * 처음 그릴 때 · 창 크기 · 장수 정보(머리줄 띠)가 바뀔 때 다시 잰다. 너무 작은 창에서도 360 아래로는 줄이지 않는다.
 */
function useRoomHeight(mobile: boolean, remeasureKey: unknown): { ref: (el: HTMLDivElement | null) => void; style: CSSProperties | undefined } {
    const node = useRef<HTMLDivElement | null>(null);
    const [top, setTop] = useState<number | null>(null);
    useEffect(() => {
        const measure = () => {
            const el = node.current;
            if (el) setTop(Math.max(0, Math.round(el.getBoundingClientRect().top + window.scrollY)));
        };
        measure();
        window.addEventListener('resize', measure);
        return () => window.removeEventListener('resize', measure);
    }, [mobile, remeasureKey]);
    const style = top == null ? undefined
        : { height: `max(360px, calc(100dvh - ${top}px${mobile ? ' - var(--bottom-nav-inset, 64px) - 1px' : ''}))` };
    return { ref: (el) => { node.current = el; }, style };
}

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
    // 지도를 주소로 연 보기(`?view=ju|commandery|county&focus=<城 id>`, K2 — K8 「지도에서 보기」 바로가기)
    const searchParams = useSearchParams();
    const mapView = useMemo(() => parseWarRoomMapView(searchParams), [searchParams]);
    const { frontInfo, generalId, refresh, serverId } = session;
    const viewport = useViewportClass();
    const mobile = viewport === 'mobile';
    const hasGeneral = frontInfo != null && generalId != null;
    const room = useRoomHeight(mobile, frontInfo);
    // 지난 순 서랍(P-W04)이 열리면 지도 보기 단추를 서랍 오른쪽으로(WarRoomTopdownMap --map-viewbar-left, K2 합의 10-01).
    // 모바일은 엿보기 시트 · 선택 알약 위로 보기 단추를 올린다(--map-viewbar-bottom).
    const [drawerOpen, setDrawerOpen] = useState(false);
    const mapStyle = mobile
        // 장수가 없을 때도 지도 바닥에 상태 판(PEEK_HEIGHT)이 서니 보기 단추를 그 위로(#1232 리뷰).
        ? ({ '--map-viewbar-bottom': `${(hasGeneral ? PEEK_HEIGHT + PILL_ROW : PEEK_HEIGHT) + 12}px` } as CSSProperties)
        : drawerOpen ? ({ '--map-viewbar-left': `${DRAWER_WIDTH}px` } as CSSProperties) : undefined;
    // 서랍 · 손잡이 · 시트가 덮은 폭 — 새 지도는 그 안을 화면 밖처럼 보고 내 위치 화살표를 덮이지 않은 가장자리에 둔다(K2 myLocationInset).
    // 데스크톱은 왼쪽 손잡이(44) · 서랍(380), 모바일은 아래 엿보기 시트 + 선택 알약.
    const drawerInset = useMemo(() => {
        if (!hasGeneral) return undefined;
        return mobile ? { bottom: PEEK_HEIGHT + PILL_ROW } : { left: drawerOpen ? DRAWER_WIDTH : DRAWER_HANDLE_WIDTH };
    }, [hasGeneral, drawerOpen, mobile]);
    const { toasts, show, remove } = useToast();
    const [refreshKey, setRefreshKey] = useState(0);
    // 명령 흐름(P-W02, K6) — 주소 ?do · slot · target 이 있으면 12순 열 자리를 흐름이 차지한다(설계서 §2.1).
    const flow = useFlowQuery();
    const turnSlots = useTurnSlots(generalId, refreshKey);
    const bump = () => {
        setRefreshKey((k) => k + 1);
        refresh();
    };

    // 시야·군단·첩보 — 서버 투영이 정한다. 조회 실패 시 레이어를 비우고, 지도 위 칩으로 알린다(누르면 다시).
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
    // 내 위치 표지의 내 장수 — 값이 같으면 같은 객체(렌더마다 새로 만들면 지도가 핀을 다시 그린다)
    const general = frontInfo?.general;
    const myGeneralName = general?.hasGeneral ? general.name : undefined;
    const myNationColor = frontInfo?.nation?.color ?? null;
    const myGeneral = useMemo(() => (myGeneralName ? {
        name: myGeneralName, nationColor: myNationColor, picture: general?.picture, imageServer: general?.imageServer,
    } : undefined), [myGeneralName, myNationColor, general?.picture, general?.imageServer]);
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

    // 층 읽기 실패 — 조용히 비우지 않고 칩(누르면 다시 읽기). 문구는 보드 V31K4MWarRoom 「시야를 못 불러 안개를 비웠습니다 — 다시」.
    const failed = (r: { error: string | null; data: { status: string } | null }) => r.error != null || r.data?.status === 'WRONG_RULE_PROFILE';
    const layerFails = [
        failed(vision) ? '시야를 못 불러 안개를 비웠습니다' : null,
        failed(corps) ? '군단을 못 불러 군단 표지를 비웠습니다' : null,
        failed(works) || failed(sieges) ? '공사 · 포위 표지를 못 불러왔습니다' : null,
    ].filter((t): t is string => t != null);

    const flowOpen = hasGeneral && flow.query.open;
    const flowPanel = flowOpen && frontInfo && generalId != null ? (
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
    ) : null;
    const turnsProps = {
        load: turnSlots.load,
        onRetry: turnSlots.reload,
        onSlot: (turnIdx: number) => flow.openFlow({ slot: turnIdx }),
        onDoNow: () => flow.openFlow({}),
    };
    const commandHere = (cityId: number) => flow.openFlow({ target: { kind: 'county', id: String(cityId) } });

    // 장수가 없으면 지도만(공개 지도) — 입구 판정(P-E01)은 셸 · 입구가 먼저 한다. 불러오는 중 · 실패를 「장수 없음」으로 보이지 않는다.
    // 데스크톱은 12순 열 자리, 모바일은 엿보기 시트 자리(지도 바닥)에 둔다 — 모바일에서 빈 지도만 남기지 않는다(#1232 리뷰).
    const noGeneral = session.loading ? <StatusView kind="loading" rows={mobile ? 2 : 6} />
        : session.error ? <StatusView kind="error" title="장수 정보를 불러오지 못했습니다" onRetry={refresh} />
        : <StatusView kind="empty" title="이 서버에 장수가 없습니다" body="장수를 만들거나 출사하면 명령 목록이 여기에 보입니다." />;

    const map = (
        <section className={styles.mapArea} style={mapStyle} aria-label="지도">
            <WarRoomMap
                fill
                refreshKey={refreshKey}
                homeCityId={frontInfo?.city?.id ?? null}
                myGeneral={myGeneral}
                myLocationInset={drawerInset}
                mapView={mapView}
                visibility={visibility}
                intelAge={intelAge}
                corps={corps.data?.corps}
                works={works.data}
                sieges={sieges.data}
                scoutable={scoutable}
                onScout={generalId != null ? (no) => void sendScout(no) : undefined}
                scoutPending={scoutPending}
            />
            {/* 지난 순 서랍(P-W04) · 내 위치(선택 카드 첫 쓰임) · 층 실패 칩 — 지도를 밀지 않고 겹친다. */}
            <div className={mobile ? styles.topRowMobile : styles.topRow}>
                {hasGeneral && mobile ? (
                    <LastTurnsDrawer mobile onOpenChange={setDrawerOpen} hrefs={{
                        court: campaignHref('court?tab=orders', serverId), yuedan: campaignHref('retinue/yuedan', serverId), records: campaignHref('records', serverId),
                    }} />
                ) : null}
                {hasGeneral && !mobile ? (
                    <HereCard city={frontInfo?.city ?? null} mobile={false} onCommandHere={commandHere}
                        countyHref={(id) => campaignHref(`territory/county/${id}`, serverId)} />
                ) : null}
                {layerFails.map((text) => (
                    <button key={text} type="button" className={styles.failChip} onClick={bump}>{`${text} — 다시`}</button>
                ))}
            </div>
            {hasGeneral && !mobile ? (
                <LastTurnsDrawer mobile={false} onOpenChange={setDrawerOpen} hrefs={{
                    court: campaignHref('court?tab=orders', serverId), yuedan: campaignHref('retinue/yuedan', serverId), records: campaignHref('records', serverId),
                }} />
            ) : null}
            {hasGeneral && mobile ? (
                <>
                    <div className={styles.pillRow} style={{ bottom: PEEK_HEIGHT + 8 }}>
                        <HereCard city={frontInfo?.city ?? null} mobile onCommandHere={commandHere}
                            countyHref={(id) => campaignHref(`territory/county/${id}`, serverId)} />
                    </div>
                    <WarRoomTurnsPeek {...turnsProps} />
                </>
            ) : null}
            {!hasGeneral && mobile ? <section className={styles.peekState} style={{ height: PEEK_HEIGHT }} aria-label="작전실 상태">{noGeneral}</section> : null}
        </section>
    );


    return (
        <GameShell title="작전실" tab={null} showBack={false} requiresHwiha={false} bleed>
            <div ref={room.ref} style={room.style} className={mobile ? styles.roomMobile : styles.room} data-flow-open={flowOpen || undefined} data-testid="war-room-layout">
                {map}
                {mobile ? flowPanel
                    : flowPanel ? <div className={styles.flowCol}>{flowPanel}</div>
                    : hasGeneral ? <WarRoomTurnsColumn {...turnsProps} />
                    : <div className={styles.turns}>{noGeneral}</div>}
            </div>
            <Toast toasts={toasts} onRemove={remove} />
        </GameShell>
    );
}
