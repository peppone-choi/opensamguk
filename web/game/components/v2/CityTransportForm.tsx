'use client';

// OPENSAM-154 (v2 R5) — v2 도시 자원 수송 제출 화면.
//
// v2-lab 아래 신규 라우트만 추가한다(레이아웃 게이트는 이미 있음, 기존 페이지 미수정).
// 결과 판정은 result-poll 규약(OPENSAM-13/135) — 202는 성공이 아니다. R4의 garrison 페이지와
// 같은 모양으로 applied / rejected(사유) / pending(폴링 시간 초과)를 서로 다른 문구로 보여준다.

import { useCallback, useEffect, useRef, useState } from 'react';
import { sameStrategicBinding, type StrategicMapRoute, type StrategicTopologyBinding } from '@opensamguk/ui';
import GameCard from '../GameCard';
import MapViewer from '../game/MapViewer';
import { api } from '../../lib/api';
import { readServerCookie } from '../../lib/serverGameUrl';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import CityLedgerPanel from './CityLedgerPanel';
import type { IntakeOutcome } from '../../lib/types';
import {
    transportRoutePins, transportRouteSummary,
    type TransportRoutePreview,
} from '../../lib/v2/cityTransport';

type Outcome = { kind: 'applied' | 'rejected' | 'pending'; message: string };

function routeMatchesBinding(route: StrategicMapRoute, binding: StrategicTopologyBinding): boolean {
    return route.worldId === binding.worldId && route.topologyRevision === binding.topologyRevision
        && route.topologyHash === binding.topologyHash;
}

export default function CityTransportForm() {
    const [generalId, setGeneralId] = useState('');
    const [fromCityId, setFromCityId] = useState('');
    const [toCityId, setToCityId] = useState('');
    const [gold, setGold] = useState('');
    const [rice, setRice] = useState('');
    const [garrison, setGarrison] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [outcome, setOutcome] = useState<Outcome | null>(null);
    const [selectedRoute, setSelectedRoute] = useState<StrategicMapRoute | null>(null);
    const routeRef = useRef<StrategicMapRoute | null>(null);
    const bindingRef = useRef<StrategicTopologyBinding | null | undefined>(undefined);
    const serverRef = useRef(readServerCookie());
    const generation = useRef(0);
    const requestActive = useRef(false);
    // OPENSAM-155 (R6) — 수송은 두 도시를 동시에 움직이므로 양쪽 원장을 같이 보여준다.
    const [ledgerRefresh, setLedgerRefresh] = useState(0);

    const clearRoute = useCallback(() => {
        routeRef.current = null;
        setSelectedRoute(null);
    }, []);

    const invalidateScope = useCallback(() => {
        generation.current += 1;
        requestActive.current = false;
        clearRoute();
        setOutcome(null);
        setSubmitting(false);
    }, [clearRoute]);

    const observeServer = useCallback(() => {
        const current = readServerCookie();
        if (current === serverRef.current) return;
        serverRef.current = current;
        bindingRef.current = undefined;
        invalidateScope();
        setLedgerRefresh(n => n + 1);
    }, [invalidateScope]);

    useEffect(() => {
        // Cookie changes have no universal event. Inspect on navigation/focus, with a local-only
        // fallback while this form is mounted, and again at every asynchronous submit boundary.
        window.addEventListener('focus', observeServer);
        window.addEventListener('popstate', observeServer);
        document.addEventListener('visibilitychange', observeServer);
        const timer = window.setInterval(observeServer, 250);
        return () => {
            window.removeEventListener('focus', observeServer);
            window.removeEventListener('popstate', observeServer);
            document.removeEventListener('visibilitychange', observeServer);
            window.clearInterval(timer);
            generation.current += 1; // An unmounted form must never submit a late preview.
        };
    }, [observeServer]);

    const handleBindingChange = useCallback((binding: StrategicTopologyBinding | null) => {
        const previous = bindingRef.current;
        bindingRef.current = binding;
        const changed = previous !== undefined && !(previous === null && binding === null)
            && !sameStrategicBinding(previous, binding);
        if ((routeRef.current && (!binding || !routeMatchesBinding(routeRef.current, binding)))
            || (requestActive.current && changed)) invalidateScope();
    }, [invalidateScope]);

    async function handleSubmit() {
        observeServer();
        const requestServer = serverRef.current;
        if (!requestServer) {
            setOutcome({ kind: 'rejected', message: '서버를 확인할 수 없습니다. 서버를 다시 선택해주세요.' });
            return;
        }
        const nums = [generalId, fromCityId, toCityId].map(Number);
        if (nums.some(n => !Number.isSafeInteger(n) || n <= 0 || n > 2147483647)) {
            setOutcome({ kind: 'rejected', message: '장수 ID / 출발 도시 / 도착 도시를 올바르게 입력해주세요.' });
            return;
        }
        const [gid, from, to] = nums;
        const amounts = { gold: Number(gold || 0), rice: Number(rice || 0), garrison: Number(garrison || 0) };
        if (Object.values(amounts).some(n => !Number.isSafeInteger(n) || n < 0)) {
            setOutcome({ kind: 'rejected', message: '수송량은 0 이상의 숫자여야 합니다.' });
            return;
        }

        setSubmitting(true);
        requestActive.current = true;
        const requestGeneration = ++generation.current;
        const stillCurrent = () => {
            observeServer();
            return requestGeneration === generation.current && requestServer === readServerCookie();
        };
        setOutcome(null);
        clearRoute();
        try {
            const payload = { fromCityId: from, toCityId: to, ...amounts };
            const preview = await api.post<TransportRoutePreview>(
                `/api/v2/city-transport/route?generalId=${gid}`, payload,
            );
            if (!stillCurrent()) return;
            const routePins = transportRoutePins(preview);
            if (preview.route) {
                // The origin comes only from the response and the request's captured server.
                // A later map binding may invalidate this path, but must never retag it.
                const scopedRoute = { ...preview.route, worldId: preview.worldId, serverId: requestServer };
                if (bindingRef.current && !routeMatchesBinding(scopedRoute, bindingRef.current)) {
                    throw new Error('세계 또는 지도가 변경되었습니다. 수송 경로를 다시 확인해주세요.');
                }
                routeRef.current = scopedRoute;
                setSelectedRoute(scopedRoute);
            }
            const result = await submitCommandAndAwaitResult(() => {
                if (!stillCurrent()) throw new Error('서버 또는 세계가 변경되었습니다.');
                return api.post<IntakeOutcome>(`/api/v2/city-transport?generalId=${gid}&expectedWorldId=${preview.worldId}`, {
                    ...payload,
                    ...routePins,
                });
            });
            if (!stillCurrent()) return;
            if (result.status === 'applied') {
                setOutcome({ kind: 'applied', message: '수송이 완료되었습니다.' });
            } else if (result.status === 'reserved') {
                setOutcome({ kind: 'pending', message: result.reason });
            } else if (result.status === 'pending') {
                setOutcome({ kind: 'pending', message: '결과를 확인할 수 없습니다(폴링 시간 초과). 잠시 후 다시 확인해주세요.' });
            } else {
                clearRoute();
                setOutcome({ kind: 'rejected', message: result.reason ?? '수송에 실패했습니다.' });
            }
        } catch (cause) {
            if (!stillCurrent()) return;
            clearRoute();
            setOutcome({ kind: 'rejected', message: cause instanceof Error ? cause.message : '수송에 실패했습니다.' });
        } finally {
            if (requestGeneration === generation.current) {
                requestActive.current = false;
                setSubmitting(false);
                setLedgerRefresh(n => n + 1);
            }
        }
    }

    const field = (label: string, value: string, set: (v: string) => void) => (
        <label key={label}>
            {label}
            <input type="number" disabled={submitting} value={value} onChange={e => {
                set(e.target.value);
                invalidateScope();
            }} />
        </label>
    );

    return (
        <GameCard>
            <CityLedgerPanel cityId={Number(fromCityId)} refreshKey={ledgerRefresh} />
            <CityLedgerPanel cityId={Number(toCityId)} refreshKey={ledgerRefresh} />
            <p>인접한 자국 도시로만 수송할 수 있고, 금·병량은 각각 5만까지입니다. 수송에는 병사 2000명이 필요합니다.</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', maxWidth: 320 }}>
                {field('장수 ID', generalId, setGeneralId)}
                {field('출발 도시 ID', fromCityId, setFromCityId)}
                {field('도착 도시 ID', toCityId, setToCityId)}
                {field('금', gold, setGold)}
                {field('병량', rice, setRice)}
                {field('도시병사', garrison, setGarrison)}
                <button disabled={submitting} onClick={() => void handleSubmit()}>
                    {submitting ? '처리 중...' : '수송'}
                </button>
            </div>
            {selectedRoute && <p>{transportRouteSummary(selectedRoute)}</p>}
            <MapViewer disallowClick refreshKey={ledgerRefresh} selectedServerRoute={selectedRoute}
                onStrategicBindingChange={handleBindingChange} selectedCityId={Number(fromCityId) || null} />
            {outcome && (
                <p
                    role="status"
                    style={{
                        marginTop: 'var(--space-sm)',
                        color: outcome.kind === 'applied' ? 'green' : outcome.kind === 'rejected' ? 'crimson' : undefined,
                    }}
                >
                    {outcome.message}
                </p>
            )}
        </GameCard>
    );
}
