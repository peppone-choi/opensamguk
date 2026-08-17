'use client';

// OPENSAM-154 (v2 R5) — v2 도시 자원 수송 제출 화면.
//
// v2-lab 아래 신규 라우트만 추가한다(레이아웃 게이트는 이미 있음, 기존 페이지 미수정).
// 결과 판정은 result-poll 규약(OPENSAM-13/135) — 202는 성공이 아니다. R4의 garrison 페이지와
// 같은 모양으로 applied / rejected(사유) / pending(폴링 시간 초과)를 서로 다른 문구로 보여준다.

import { useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import { api } from '../../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../../lib/commandSubmit';
import CityLedgerPanel from '../../../../components/v2/CityLedgerPanel';
import type { IntakeOutcome } from '../../../../lib/types';

type Outcome = { kind: 'applied' | 'rejected' | 'pending'; message: string };

export default function V2CityTransportPage() {
    const [generalId, setGeneralId] = useState('');
    const [fromCityId, setFromCityId] = useState('');
    const [toCityId, setToCityId] = useState('');
    const [gold, setGold] = useState('');
    const [rice, setRice] = useState('');
    const [garrison, setGarrison] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [outcome, setOutcome] = useState<Outcome | null>(null);
    // OPENSAM-155 (R6) — 수송은 두 도시를 동시에 움직이므로 양쪽 원장을 같이 보여준다.
    const [ledgerRefresh, setLedgerRefresh] = useState(0);

    async function handleSubmit() {
        const nums = [generalId, fromCityId, toCityId].map(Number);
        if (nums.some(n => !Number.isFinite(n))) {
            setOutcome({ kind: 'rejected', message: '장수 ID / 출발 도시 / 도착 도시를 올바르게 입력해주세요.' });
            return;
        }
        const [gid, from, to] = nums;
        const amounts = { gold: Number(gold || 0), rice: Number(rice || 0), garrison: Number(garrison || 0) };
        if (Object.values(amounts).some(n => !Number.isFinite(n) || n < 0)) {
            setOutcome({ kind: 'rejected', message: '수송량은 0 이상의 숫자여야 합니다.' });
            return;
        }

        setSubmitting(true);
        setOutcome(null);
        try {
            const result = await submitCommandAndAwaitResult(() =>
                api.post<IntakeOutcome>(`/api/v2/city-transport?generalId=${gid}`, {
                    fromCityId: from,
                    toCityId: to,
                    ...amounts,
                }),
            );
            if (result.status === 'applied') {
                setOutcome({ kind: 'applied', message: '수송이 완료되었습니다.' });
            } else if (result.status === 'reserved') {
                setOutcome({ kind: 'pending', message: result.reason });
            } else if (result.status === 'pending') {
                setOutcome({ kind: 'pending', message: '결과를 확인할 수 없습니다(폴링 시간 초과). 잠시 후 다시 확인해주세요.' });
            } else {
                setOutcome({ kind: 'rejected', message: result.reason ?? '수송에 실패했습니다.' });
            }
        } catch (cause) {
            setOutcome({ kind: 'rejected', message: cause instanceof Error ? cause.message : '수송에 실패했습니다.' });
        } finally {
            setSubmitting(false);
            setLedgerRefresh(n => n + 1);
        }
    }

    const field = (label: string, value: string, set: (v: string) => void) => (
        <label key={label}>
            {label}
            <input type="number" value={value} onChange={e => set(e.target.value)} />
        </label>
    );

    return (
        <Shell>
            <div className="page-content">
                <h1>v2-lab · 도시 자원 수송</h1>
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
            </div>
        </Shell>
    );
}
