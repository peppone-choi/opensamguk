'use client';

import { useMemo, useState } from 'react';
import type { CommanderyVisibility } from '@opensamguk/ui';
import { Panel } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import Toast from '@/components/Toast';
import MainRecordZone from '@/components/game/MainRecordZone';
import MessagePanel from '@/components/game/MessagePanel';
import CountyPanel from '@/components/hwiha/CountyPanel';
import GeneralRoster from '@/components/hwiha/GeneralRoster';
import LastTurnPanel from '@/components/hwiha/LastTurnPanel';
import StandingBar from '@/components/hwiha/StandingBar';
import TurnList from '@/components/hwiha/TurnList';
import WarRoomMap from '@/components/hwiha/WarRoomMap';
import { useToast } from '@/hooks/useToast';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';
import { reserveHwihaScout } from '@/lib/hwiha-scout';
import { useHwihaSession } from '@/lib/hwiha-session';

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
    const session = useHwihaSession();
    const { frontInfo, generalId, isHwihaWorld, refresh } = session;
    const { toasts, show, remove } = useToast();
    const [refreshKey, setRefreshKey] = useState(0);
    const bump = () => {
        setRefreshKey((k) => k + 1);
        refresh();
    };

    // 시야·군단·첩보 — 서버 투영이 정한다. 조회 실패 시 레이어를 비운다.
    const vision = useHwihaRead((id, signal) => api.hwihaVisibility(id, signal), [refreshKey]);
    const corps = useHwihaRead((id, signal) => api.hwihaCorps(id, signal), [refreshKey]);
    const sieges = useHwihaRead((id, signal) => api.hwihaSieges(id, signal), [refreshKey]);
    const works = useHwihaRead((id, signal) => api.hwihaWorks(id, signal), [refreshKey]);
    const scout = useHwihaRead((id, signal) => api.hwihaScoutOptions(id, signal), [refreshKey]);
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
            const result = await reserveHwihaScout(generalId, option);
            show(result.message, result.ok ? 'success' : 'error');
            if (result.ok) bump();
        } finally {
            setScoutPending(false);
        }
    };

    return (
        <HwihaShell title="작전실" tab={null} showBack={false} requiresHwiha={false}>
            <div
                style={{
                    padding: 12,
                    display: 'grid',
                    gridTemplateColumns: 'minmax(0, 1fr) 420px',
                    gap: 12,
                    alignItems: 'start',
                }}
            >
                <div style={{ display: 'grid', gap: 12, minWidth: 0 }}>
                    {/* 안개는 서버 시야 투영(군국 단위)만 따른다. */}
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
                    {vision.error || vision.data?.status === 'WRONG_RULE_PROFILE' ? <p role="status">시야를 불러오지 못해 안개 레이어를 비웠습니다.</p> : null}
                    {corps.error || corps.data?.status === 'WRONG_RULE_PROFILE' ? <p role="status">군단을 불러오지 못해 군단 레이어를 비웠습니다.</p> : null}
                    {isHwihaWorld && <p style={{ margin: 0, color: 'var(--muted)', fontSize: 12 }}>
                        요격·회피 반응은 현재 기록만 남으며 이동이나 전투에 효과가 없습니다.
                    </p>}
                    {frontInfo && generalId != null ? (
                        <>
                            <CountyPanel city={frontInfo.city} isHwihaWorld={isHwihaWorld} />
                            <StandingBar />
                            {isHwihaWorld ? <LastTurnPanel /> : null}
                            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 12 }}>
                                <Panel style={{ padding: 0, minWidth: 0 }}>
                                    <MainRecordZone recentRecord={frontInfo.recentRecord} />
                                </Panel>
                                <MessagePanel
                                    generalId={generalId}
                                    nationId={frontInfo.general.nationId}
                                    refreshKey={refreshKey}
                                    onToast={show}
                                />
                            </div>
                        </>
                    ) : null}
                </div>

                {generalId != null && frontInfo ? (
                    <div style={{ display: 'grid', gap: 12, alignContent: 'start' }}>
                    <GeneralRoster />
                    <TurnList
                        generalId={generalId}
                        nationId={frontInfo.general.nationId}
                        refreshKey={refreshKey}
                        isHwihaWorld={isHwihaWorld}
                        onToast={show}
                        onReserved={bump}
                    />
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
        </HwihaShell>
    );
}
