'use client';

import { useState } from 'react';
import { Chip, KV, Panel, SectionHeader } from '@opensamguk/ui';
import HwihaShell from '@/components/HwihaShell';
import { HwihaEmpty, hwihaReadNotice } from '@/components/hwiha/HwihaStates';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { type HwihaSiege, type HwihaRoadFort, useHwihaRead } from '@/lib/hwiha-reads';
import { useHwihaSession } from '@/lib/hwiha-session';

const number = new Intl.NumberFormat('ko-KR');
const failureText: Record<string, string> = {
    WRONG_RULE_PROFILE: '이 세계에서는 공성 입력을 사용할 수 없습니다.',
    NOT_BESIEGING: '지휘 중인 포위가 없습니다.',
    STATE_UNAVAILABLE: '포위 상태를 확인할 수 없습니다.',
    BATTLEFIELD_UNAVAILABLE: '이 縣의 전장을 만들 수 없어 강공할 수 없습니다.',
    UNIT_UNAVAILABLE: '강공에 쓸 수 없는 병종이 포함돼 있습니다.',
    ASSAULT_NOT_READY: '포위한 지 한 달(3순)이 지나야 강공할 수 있습니다.',
    REFUSED: '성 안의 사기와 민심이 높아 항복 권고를 거절했습니다.',
    BATTLE_PENDING: '조우 전투 중에는 공성 행동을 할 수 없습니다.',
};
const eventText: Record<string, string> = {
    START: '포위 시작', TURN: '포위 유지', FALLEN: '함락', LIFTED: '포위 해제',
    DEMAND_ACCEPTED: '항복 권고 수락', DEMAND_REFUSED: '항복 권고 거절',
    ASSAULT_CAPTURED: '강공 함락', ASSAULT_REPULSED: '강공 격퇴',
};
function label(value: unknown): string { return typeof value === 'string' ? (eventText[value] ?? value) : '공성 사건'; }
function phase(row: Record<string, unknown>): string {
    const year = row.year, month = row.month, turn = row.phase;
    return typeof year === 'number' && typeof month === 'number' && typeof turn === 'number'
        ? `${year}년 ${month}월 ${['상순', '중순', '하순'][turn - 1] ?? `${turn}순`}` : '시각 확인 불가';
}
function rejectedReason(reason: string | undefined, code: unknown): string {
    return (typeof code === 'string' && failureText[code]) || (reason && failureText[reason]) || reason || '공성 입력을 처리할 수 없습니다.';
}

export default function SiegePage() {
    const { generalId, refresh } = useHwihaSession();
    const [refreshKey, setRefreshKey] = useState(0);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ kind: 'error' | 'status'; text: string } | null>(null);
    const read = useHwihaRead((id, signal) => api.hwihaSieges(id, signal), [refreshKey]);
    const roadRead = useHwihaRead((id, signal) => api.hwihaRoadForts(id, signal), [refreshKey]);
    const rows = read.data?.sieges ?? [];
    const roadForts = roadRead.data?.forts ?? [];
    const problem = hwihaReadNotice(read, read.data?.status);
    const act = async (siege: HwihaSiege, action: 'action.assault' | 'action.demandSurrender') => {
        if (generalId == null || !siege.canAct || busy) return;
        setBusy(true); setNotice(null);
        try {
            const reserved = await api.reservedCommands(generalId);
            const used = new Set(reserved.slots.map((slot) => slot.turnIdx));
            const turnIdx = Array.from({ length: 12 }, (_, i) => i).find((i) => !used.has(i));
            if (turnIdx === undefined) { setNotice({ kind: 'error', text: '명령 목록 12순이 모두 찼습니다.' }); return; }
            const result = await submitCommandAndAwaitResult(() => api.command(action, {}, generalId, turnIdx));
            if (result.status === 'rejected') {
                setNotice({ kind: 'error', text: rejectedReason(result.reason, result.result?.result.code) });
            } else {
                setNotice({ kind: 'status', text: result.status === 'applied'
                    ? '공성 행동이 처리되었습니다.' : `${action === 'action.assault' ? '강공' : '항복 권고'}을 ${turnIdx + 1}순에 예약했습니다. 실행 결과는 턴이 지난 뒤 확인해 주세요.` });
                setRefreshKey((k) => k + 1); refresh();
            }
        } catch (error) {
            setNotice({ kind: 'error', text: error instanceof Error ? error.message : '공성 입력을 제출하지 못했습니다.' });
        } finally { setBusy(false); }
    };
    const besiegeRoadFort = async (fort: HwihaRoadFort) => {
        if (generalId == null || !fort.canBesiege || busy) return;
        setBusy(true); setNotice(null);
        try {
            const reserved = await api.reservedCommands(generalId);
            const used = new Set(reserved.slots.map((slot) => slot.turnIdx));
            const turnIdx = Array.from({ length: 12 }, (_, i) => i).find((i) => !used.has(i));
            if (turnIdx === undefined) { setNotice({ kind: 'error', text: '명령 목록 12순이 모두 찼습니다.' }); return; }
            const result = await submitCommandAndAwaitResult(() =>
                api.command('action.siegeRoadFort', { fortId: fort.id }, generalId, turnIdx));
            if (result.status === 'rejected') setNotice({ kind: 'error', text: result.reason ?? '보루 포위 예약을 처리할 수 없습니다.' });
            else {
                setNotice({ kind: 'status', text: result.status === 'applied' ? '보루 포위를 시작했습니다.' :
                    `${turnIdx + 1}순에 보루 포위를 예약했습니다.` });
                setRefreshKey((key) => key + 1); refresh();
            }
        } catch (error) {
            setNotice({ kind: 'error', text: error instanceof Error ? error.message : '보루 포위를 예약하지 못했습니다.' });
        } finally { setBusy(false); }
    };

    return <HwihaShell title="공성" tab="방침">
        <div style={{ padding: 12, display: 'grid', gap: 12 }}>
            <Panel style={{ padding: 12 }}>
                <SectionHeader title="포위 중인 성" sub="관여한 포위와 지난 결과" actions={<Chip>{`${rows.length}곳`}</Chip>} />
                {notice && <p role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p>}
                {problem && <HwihaEmpty>{problem}</HwihaEmpty>}
                {!problem && rows.length === 0 && <HwihaEmpty>관여한 포위가 없습니다.</HwihaEmpty>}
            </Panel>
            {!problem && rows.map((siege) => <Panel key={siege.countyId} style={{ padding: 12 }}>
                <SectionHeader title={siege.countyName ?? `縣 ${siege.countyId}`} sub={`${siege.besieger.nationName ?? '포위군'} → ${siege.defenderNationName ?? '수비군'}`}
                    actions={<Chip tone={siege.status === 'ACTIVE' ? 'rust' : 'moss'}>{siege.status === 'ACTIVE' ? '포위 중' : siege.status === 'FALLEN' ? '함락' : siege.status === 'LIFTED' ? '포위 해제' : '[미정]'}</Chip>} />
                <KV items={[
                    { k: '포위 누적', v: `${siege.turns}순` },
                    { k: '강공까지', v: siege.status !== 'ACTIVE' ? '종료' : siege.turns >= 3 ? '가능' : `${3 - siege.turns}순` },
                    { k: '포위 지휘관', v: siege.besieger.name ?? `장수 ${siege.besieger.generalId}` },
                    { k: '성 안 수비', v: `${number.format(siege.garrison)}명` },
                    { k: '성 안 사기', v: `${Math.round(siege.morale / 100)}%` },
                    { k: '성 안 군량', v: siege.grain == null ? '확인 불가' : number.format(siege.grain) },
                    { k: '민심', v: `${number.format(siege.trust)}` },
                    { k: '포위 병력', v: siege.besiegerTroops == null ? '확인 불가' : `${number.format(siege.besiegerTroops)}명` },
                    { k: '포위군 급식', v: siege.besiegerFed == null ? '확인 불가' : siege.besiegerFed ? '충분' : '부족' },
                ]} />
                {siege.status === 'ACTIVE' && <>
                    <p style={{ fontSize: 13, color: 'var(--text-2)' }}>
                        항복 권고 조건(현재 사기·민심): {siege.surrenderDemandAccepted ? '수락 가능' : '현재는 거절 예상'}
                    </p>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        <button className="os-button os-button--danger os-button--sm" disabled={!siege.canAct || busy} onClick={() => void act(siege, 'action.assault')}>강공 예약</button>
                        <button className="os-button os-button--primary os-button--sm" disabled={!siege.canAct || busy} onClick={() => void act(siege, 'action.demandSurrender')}>항복 권고 예약</button>
                    </div>
                    {!siege.canAct && <p style={{ fontSize: 12, color: 'var(--muted)' }}>포위 지휘관만 행동을 예약할 수 있습니다.</p>}
                </>}
                <h3 style={{ fontSize: 14, marginTop: 16 }}>포위 기록</h3>
                {siege.timeline.length === 0 ? <HwihaEmpty>기록이 없습니다.</HwihaEmpty> : <ol style={{ margin: 0, paddingLeft: 22 }}>
                    {siege.timeline.map((entry, index) => <li key={index} style={{ padding: '4px 0', fontSize: 13 }}>
                        {phase(entry)} · {label(entry.event)}
                        {typeof entry.morale === 'number' ? ` · 사기 ${Math.round(entry.morale / 100)}%` : ''}
                        {typeof entry.garrison === 'number' ? ` · 수비 ${number.format(entry.garrison)}` : ''}
                    </li>)}
                </ol>}
            </Panel>)}
            <Panel style={{ padding: 12 }}>
                <SectionHeader title="도로 보루" sub="현과 별개로 소유하고 포위하는 길목" actions={<Chip>{`${roadForts.length}곳`}</Chip>} />
                {hwihaReadNotice(roadRead, roadRead.data?.status) &&
                    <HwihaEmpty>{hwihaReadNotice(roadRead, roadRead.data?.status)}</HwihaEmpty>}
                {!hwihaReadNotice(roadRead, roadRead.data?.status) && roadForts.length === 0 &&
                    <HwihaEmpty>보이는 도로 보루가 없습니다.</HwihaEmpty>}
                {roadForts.map((fort) => <div key={fort.id} style={{ padding: '8px 0', borderTop: '1px solid var(--border)' }}>
                    <strong>도로 보루 · {fort.provinceId}</strong>
                    <KV items={[
                        { k: '위치', v: `${fort.row}, ${fort.col}` },
                        { k: '소유 세력', v: `${fort.ownerNationId}` },
                        { k: '성벽', v: `${fort.wall}` },
                        { k: '포위 진척', v: fort.besiegerGeneralId == null ? '포위 없음' : `${fort.siegeProgress}%` },
                    ]} />
                    {fort.canBesiege && <button className="os-button os-button--danger os-button--sm"
                        disabled={busy} onClick={() => void besiegeRoadFort(fort)}>보루 포위 예약</button>}
                </div>)}
            </Panel>
        </div>
    </HwihaShell>;
}
