'use client';
/* Hallmark · component: court form · genre: atmospheric · theme: existing field headquarters
 * states: default · hover · focus · active · disabled · loading · error · success
 * pre-emit critique: P4 H4 E4 S4 R5 V3
 */
import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { DispatchOptionsResponse, DispatchPendingResponse, HwihaPhase, IntakeOutcome } from '../../lib/types';
import styles from './HwihaCourtForm.module.css';
import HwihaLegacyCourtForm from './HwihaLegacyCourtForm';
import HwihaLegacyStratagemForm from './HwihaLegacyStratagemForm';

const statusLabels = { PENDING: '응답 대기', ACCEPTED: '수락', REFUSED: '거절', CANCELLED: '취소' };
const phaseText = (phase: HwihaPhase) => `${phase.year}년 ${phase.month}월 ${['상순', '중순', '하순'][phase.phase - 1]}`;

export default function HwihaCourtForm({ generalId, onReserved, refreshKey = 0 }: {generalId: number; refreshKey?: number; onReserved?: () => void}) {
    const [target, setTarget] = useState<number | undefined>();
    const [county, setCounty] = useState<number | undefined>();
    const [options, setOptions] = useState<DispatchOptionsResponse | null>(null);
    const [pending, setPending] = useState<DispatchPendingResponse | null>(null);
    const [optionsError, setOptionsError] = useState<string | null>(null);
    const [pendingError, setPendingError] = useState<string | null>(null);
    const [notice, setNotice] = useState<{kind: 'error' | 'success' | 'waiting'; text: string} | null>(null);
    const [busy, setBusy] = useState(false);
    const [refresh, setRefresh] = useState(0);
    const [tracking, setTracking] = useState<string | null>(null);
    const submitting = useRef(false);
    const afterChange = useRef(onReserved);
    afterChange.current = onReserved;
    const mounted = useRef(true);
    useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
    useEffect(() => {
        let active = true;
        setOptions(null); setOptionsError(null); setCounty(undefined);
        api.dispatchOptions(generalId, target).then(data => {
            if (active) setOptions(data);
        }).catch(() => { if (active) setOptionsError('발령 대상을 불러오지 못했습니다. 다시 확인해 주세요.'); });
        return () => { active = false; };
    }, [generalId, target, refresh, refreshKey]);
    useEffect(() => {
        let active = true;
        setPending(null); setPendingError(null);
        api.dispatchPending(generalId).then(data => {
            if (!active) return;
            setPending(data);
            if (!data.result) setPendingError('발령 상태를 확인할 수 없습니다. 다시 확인해 주세요.');
            if (data.queued) setTracking(data.queued.requestId);
        }).catch(() => { if (active) setPendingError('발령 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); });
        return () => { active = false; };
    }, [generalId, refresh, refreshKey]);
    useEffect(() => {
        if (!tracking || busy) return;
        let active = true;
        let timer: ReturnType<typeof setTimeout>;
        const check = async () => {
            try {
                const result = await api.commandResult(tracking);
                if (!active) return;
                if (result.status === 'RESOLVED') {
                    setNotice({kind: result.ok ? 'success' : 'error', text: result.ok ? '발령 처리가 완료되었습니다.' : result.reason ?? '발령을 처리할 수 없습니다.'});
                    setTracking(null); setRefresh(n => n + 1); afterChange.current?.();
                    return;
                }
            } catch { /* Keep the acknowledged request; a read failure must never resubmit it. */ }
            if (active) timer = setTimeout(check, 3000);
        };
        timer = setTimeout(check, 3000);
        return () => { active = false; clearTimeout(timer); };
    }, [tracking, busy]);

    async function submit(send: () => Promise<IntakeOutcome>) {
        if (submitting.current) return;
        submitting.current = true; setBusy(true); setNotice(null);
        let requestId: string | null = null;
        try {
            const result = await submitCommandAndAwaitResult(async () => {
                const accepted = await send();
                if ('requestId' in accepted && typeof accepted.requestId === 'string') requestId = accepted.requestId;
                return accepted;
            });
            if (!mounted.current) return;
            if (result.status === 'applied') setNotice({kind: 'success', text: '처리가 완료되었습니다.'});
            else if (result.status === 'rejected') setNotice({kind: 'error', text: result.reason ?? '입력을 처리할 수 없습니다.'});
            else {
                setNotice({kind: 'waiting', text: result.status === 'reserved' ? '접수되었습니다. 주공의 다음 개인 턴에 발령합니다.' : '처리 결과를 기다리고 있습니다. 다시 제출하지 않아도 됩니다.'});
                setTracking(requestId);
            }
            setRefresh(n => n + 1); afterChange.current?.();
        } catch (error) {
            if (mounted.current) {
                if (requestId) {
                    setTracking(requestId);
                    setNotice({kind:'waiting', text:'접수된 입력의 결과를 다시 확인하고 있습니다. 다시 제출하지 않아도 됩니다.'});
                    setRefresh(n => n + 1);
                } else setNotice({kind:'error', text: error instanceof Error ? error.message : '요청에 실패했습니다.'});
            }
        } finally { submitting.current = false; if (mounted.current) setBusy(false); }
    }
    const selected = options?.counties.find(row => row.countyId === county);
    const queue = pending?.queued ?? options?.queued;
    const canIssue = !busy && !tracking && !!pending?.result && !!options?.result && !!target && !!selected?.available && !queue;
    return <div className={styles.court} aria-busy={busy}>
        <p className={styles.guide}>발령과 응답은 개인 행동 예약과 별개입니다.</p>
        {notice && <p className={styles.notice} data-state={notice.kind} role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p>}
        {queue && <p className={styles.notice} role="status">발령 접수됨 · 주공의 다음 개인 턴을 기다립니다.</p>}
        <section aria-labelledby="court-received">
            <h3 id="court-received">발령 현황</h3>
            {pendingError && <p role="alert">{pendingError}</p>}
            {!pending && !pendingError && <p role="status">발령 상태를 불러오는 중입니다.</p>}
            {pending?.result && pending.dispatches.length === 0 && <p className={styles.guide}>표시할 발령이 없습니다.</p>}
            {pending?.result && pending.dispatches.map(row => <article key={row.dispatchId} className={styles.order}>
                <strong>{row.targetId === generalId ? '나에게 온 발령' : `${row.targetLabel ?? '직속 장수'} 발령`} · {row.countyLabel ?? '목적지 이름 확인 중'}</strong>
                <p>{row.issuerLabel ?? '주공 이름 확인 중'} · {statusLabels[row.status]} · 응답 기한 {phaseText(row.dueAt)}</p>
                {row.status === 'PENDING' && row.targetId === generalId && <>
                    <p className={styles.guide}>기한까지 응답하지 않으면 자동 수락합니다. 거절하면 충성도와 명망이 감소합니다.</p>
                    {row.currentFailure && <p role="status">현재 관계나 목적지 조건으로 응답할 수 없습니다. 상태를 다시 확인해 주세요.</p>}
                    <div className={styles.actions}>
                        <button className="os-button os-button--primary" disabled={busy || !!tracking || !!row.currentFailure} onClick={() => void submit(() => api.courtDispatchReply(generalId, {dispatchId:row.dispatchId, accept:true}))}>수락</button>
                        <button className="os-button os-button--ghost" disabled={busy || !!tracking || !!row.currentFailure} onClick={() => void submit(() => api.courtDispatchReply(generalId, {dispatchId:row.dispatchId, accept:false}))}>거절</button>
                    </div>
                </>}
            </article>)}
        </section>
        <section aria-labelledby="court-issue">
            <h3 id="court-issue">새 발령</h3>
            {!options && !optionsError && <p role="status">발령 대상을 불러오는 중입니다.</p>}
            {optionsError && <p role="alert">{optionsError}</p>}
            {options && !options.result && <p role="status">{options.reason ?? '발령 권한을 확인할 수 없습니다.'}</p>}
            {options?.result && options.code && <p role="status">{options.reason ?? '현재 새 발령을 접수할 수 없습니다.'}</p>}
            {options?.result && <>
                {options.targets.length === 0 ? <p className={styles.guide}>발령할 수 있는 직속 사람 장수가 없습니다.</p> : <label>직속 장수
                    <select className="os-inset" aria-label="직속 장수" value={target ?? ''} disabled={busy || !!tracking || !!queue}
                        onChange={event => { setTarget(event.target.value ? Number(event.target.value) : undefined); setCounty(undefined); setOptions(null); }}>
                        <option value="">장수를 선택하세요</option>
                        {options.targets.map(row => <option value={row.generalId} key={row.generalId}>{row.label}</option>)}
                    </select>
                </label>}
                {target && <label>발령할 현
                    <select className="os-inset" aria-label="발령할 현" value={county ?? ''} disabled={busy || !!tracking || !!queue}
                        onChange={event => setCounty(event.target.value ? Number(event.target.value) : undefined)}>
                        <option value="">현을 선택하세요</option>
                        {options.counties.map(row => <option value={row.countyId} key={row.countyId}>{row.label}{row.available ? '' : ' · 발령 불가'}</option>)}
                    </select>
                    {options.counties.length === 0 && <span className={styles.guide}>발령 가능한 아군 행정 현이 없습니다.</span>}
                </label>}
                {selected && !selected.available && <p role="status">{selected.reason ?? '이 현으로 발령할 수 없습니다.'}</p>}
                <button className="os-button os-button--primary" disabled={!canIssue}
                    onClick={() => { if (canIssue) void submit(() => api.courtDispatch(generalId, {targetGeneralId:target!, countyId:county!})); }}>
                    {busy ? '처리 중…' : '발령 접수'}
                </button>
            </>}
        </section>
        <HwihaLegacyCourtForm generalId={generalId} refreshKey={refreshKey + refresh} onReserved={onReserved} />
        <HwihaLegacyStratagemForm generalId={generalId} refreshKey={refreshKey + refresh} onReserved={onReserved} />
        <button className="os-button os-button--ghost" disabled={busy} onClick={() => setRefresh(n => n + 1)}>상태 다시 확인</button>
    </div>;
}
