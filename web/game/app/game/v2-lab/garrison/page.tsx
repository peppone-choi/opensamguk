'use client';

// OPENSAM-153 (v2 R4) — v2 도시병사 보충 제출 화면.
//
// v2-lab 네임스페이스 아래 신규 라우트만 추가한다(레이아웃 게이트는 이미 있음, 기존 페이지
// 미수정). 엔드포인트(`/api/v2/garrison-recruit`)는 lib/api.ts에 아직 없으므로 api.post를
// 그대로 재사용해 같은 프록시 호출 규약(same-origin, httpOnly 쿠키 인증)을 따른다.
//
// 결과 판정은 result-poll 규약(OPENSAM-13/135)을 그대로 쓴다: 202는 성공이 아니다.
// submitCommandAndAwaitResult가 이미 성공/거절/미확정(pending)을 구분해 반환하므로 그대로
// 재사용하고, 세 상태를 서로 다른 문구로 보여준다(타임아웃을 성공으로 뭉개지 않음).

import { useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import { api } from '../../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../../lib/commandSubmit';
import type { IntakeOutcome } from '../../../../lib/types';

type Outcome = { kind: 'applied' | 'rejected' | 'pending'; message: string };

export default function V2GarrisonRecruitPage() {
    const [generalId, setGeneralId] = useState('');
    const [cityId, setCityId] = useState('');
    const [amount, setAmount] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [outcome, setOutcome] = useState<Outcome | null>(null);

    async function handleSubmit() {
        const gid = Number(generalId);
        const cid = Number(cityId);
        const amt = Number(amount);
        if (!Number.isFinite(gid) || !Number.isFinite(cid) || !Number.isFinite(amt)) {
            setOutcome({ kind: 'rejected', message: '장수 ID / 도시 ID / 보충 인원을 올바르게 입력해주세요.' });
            return;
        }

        setSubmitting(true);
        setOutcome(null);
        try {
            const result = await submitCommandAndAwaitResult(() =>
                api.post<IntakeOutcome>(`/api/v2/garrison-recruit?generalId=${gid}`, { cityId: cid, amount: amt }),
            );
            if (result.status === 'applied') {
                setOutcome({ kind: 'applied', message: '병사 보충이 완료되었습니다.' });
            } else if (result.status === 'reserved') {
                setOutcome({ kind: 'pending', message: result.reason });
            } else if (result.status === 'pending') {
                setOutcome({ kind: 'pending', message: '결과를 확인할 수 없습니다(폴링 시간 초과). 잠시 후 다시 확인해주세요.' });
            } else {
                setOutcome({ kind: 'rejected', message: result.reason ?? '보충에 실패했습니다.' });
            }
        } catch (cause) {
            setOutcome({ kind: 'rejected', message: cause instanceof Error ? cause.message : '보충에 실패했습니다.' });
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <Shell>
            <div className="page-content">
                <h1>v2-lab · 도시병사 보충</h1>
                <GameCard>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', maxWidth: 320 }}>
                        <label>
                            장수 ID
                            <input type="number" value={generalId} onChange={e => setGeneralId(e.target.value)} />
                        </label>
                        <label>
                            도시 ID
                            <input type="number" value={cityId} onChange={e => setCityId(e.target.value)} />
                        </label>
                        <label>
                            보충 인원
                            <input type="number" value={amount} onChange={e => setAmount(e.target.value)} />
                        </label>
                        <button disabled={submitting} onClick={() => void handleSubmit()}>
                            {submitting ? '처리 중...' : '보충'}
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
