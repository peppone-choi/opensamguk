'use client';

import { useState } from 'react';
import { Panel } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import Toast from '@/components/Toast';
import MainRecordZone from '@/components/game/MainRecordZone';
import MessagePanel from '@/components/game/MessagePanel';
import CountyPanel from '@/components/hwiha/CountyPanel';
import GeneralRoster from '@/components/hwiha/GeneralRoster';
import StandingBar from '@/components/hwiha/StandingBar';
import TurnList from '@/components/hwiha/TurnList';
import WarRoomMap from '@/components/hwiha/WarRoomMap';
import { useToast } from '@/hooks/useToast';
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
                    {/* 시야 조회가 붙기 전에는 안개를 그리지 않는다 — 없는 시야를 지어내지 않는다. */}
                    <WarRoomMap homeCityId={frontInfo?.city?.id ?? null} visibility={null} />
                    {frontInfo && generalId != null ? (
                        <>
                            <CountyPanel city={frontInfo.city} isHwihaWorld={isHwihaWorld} />
                            <StandingBar />
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
