'use client';

import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { BattleRoomUnavailable } from '@/components/battle/BattleHub';
import { BattleJoin } from '@/components/battle/BattleJoin';
import { BattleLive } from '@/components/battle/BattleLive';
import { useBattleSession } from '@/lib/battle/use-battle-session';
import { useGameSession } from '@/lib/campaign-session';
import { useServerGameUrl } from '@/lib/serverGameUrl';

/**
 * 전투 방(P-C03 참가 대기 · 배치 / P-C05 실시간 전투, 한 경로의 단계) — K6 설계서 §3.5 · §3.6, 보드 V31K6v2BattleJoin · MBattleJoin.
 *
 * 들어오는 길은 전투 목록(K6-11 `/api/battles/active`, C2 대기)의 행이다 — 그 행이 `?world=<worldId>`를 붙인다.
 * 전투 세계 번호가 없거나, 서버가 전투를 열지 않으면(join-ticket 꺼짐 · 거절, WS 실패) 지금처럼 「전투가 열리지 않습니다」뿐이다
 * (운영 기본 BATTLE_JOIN_TICKET_ENABLED=false — 가짜 전투 없음). 열리면 v2 SNAPSHOT(초안, lib/battle/protocol.ts)으로 참가 · 배치를 그린다.
 * 배치 단계(SNAPSHOT deployment 있음)는 참가 · 배치(P-C03), 그 뒤(진행 중)는 실시간 전투(P-C05)를 그린다.
 */
export default function BattleRoomPage() {
    const router = useRouter();
    const hubHref = useServerGameUrl('corps/battle');
    const params = useParams<{ id: string }>();
    const search = useSearchParams();
    const { serverId } = useGameSession();
    const worldParam = search?.get('world') ?? null;
    const worldId = worldParam != null && /^(0|[1-9]\d{0,9})$/.test(worldParam) ? Number(worldParam) : null;
    const battleId = typeof params?.id === 'string' ? decodeURIComponent(params.id) : '';
    const { session, move, command } = useBattleSession(serverId ?? null, worldId, battleId);

    return (
        <GameShell title="전투">
            {session.state === 'unavailable' ? <BattleRoomUnavailable onBack={() => router.push(hubHref)} /> : null}
            {session.state === 'connecting' ? <StatusView kind="loading" rows={4} /> : null}
            {session.state === 'closed' ? (
                <div data-testid="battle-room">
                    <StatusView kind="error" title="전투 연결이 끊겼습니다" body="안 고친 부곡은 서버가 정한 기본 배치대로 섭니다." onRetry={() => window.location.reload()} />
                    <button type="button" className="os-button" onClick={() => router.push(hubHref)}>전투 · 부재 대비로</button>
                </div>
            ) : null}
            {session.state === 'ready' ? (
                session.view.revision != null ? (
                    <BattleJoin view={session.view} terrainInputSha256={session.snapshot.field.terrainInputSha256} pending={session.pending} notice={session.notice} onMove={move} />
                ) : (
                    <BattleLive view={session.live} terrainInputSha256={session.snapshot.field.terrainInputSha256} pendingCommand={session.pendingCommand} notice={session.notice} onCommand={command} />
                )
            ) : null}
        </GameShell>
    );
}
