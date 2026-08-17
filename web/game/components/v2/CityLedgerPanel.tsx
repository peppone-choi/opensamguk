'use client';

// OPENSAM-155 (v2 R6) — 도시 원장(금·병량·도시병사) 표시 패널.
//
// R1~R5는 전부 백엔드였고 유저는 자기 도시의 원장을 어디서도 볼 수 없었다. 값을 입력하는 화면
// (징병·수송)이 정작 "지금 얼마 있는지"를 감추고 있으면 유저는 매번 실패로 잔액을 알아내야 한다.
// 그래서 조작 패널 위에 얹는 read-only 필드로 만든다(설계안 §9.2 R6).
//
// read 전용 — 이 컴포넌트는 어떤 mutation도 하지 않는다. 게이트가 닫혀 있으면 엔드포인트가 404이므로
// 그 상태를 성공(0/0/0)으로 뭉개지 않고 그대로 문구로 드러낸다.

import { useCallback, useEffect, useState } from 'react';
import { fetchCityLedger, formatLedgerNumber, type CityLedgerView } from '../../lib/v2/cityLedger';

interface Props {
    /** 표시할 도시. 비어 있으면(NaN/0 이하) 아무것도 조회하지 않는다. */
    cityId: number;
    /** 조작 후 갱신을 유도하는 키 — 값이 바뀌면 다시 조회한다. */
    refreshKey?: number;
}

export default function CityLedgerPanel({ cityId, refreshKey = 0 }: Props) {
    const [ledger, setLedger] = useState<CityLedgerView | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    const valid = Number.isFinite(cityId) && cityId > 0;

    const load = useCallback(async () => {
        if (!valid) {
            setLedger(null);
            setError(null);
            return;
        }
        setLoading(true);
        try {
            setLedger(await fetchCityLedger(cityId));
            setError(null);
        } catch (cause) {
            setLedger(null);
            setError(cause instanceof Error ? cause.message : '도시 원장을 불러오지 못했습니다.');
        } finally {
            setLoading(false);
        }
    }, [cityId, valid]);

    useEffect(() => {
        void load();
    }, [load, refreshKey]);

    return (
        <div data-testid="v2-city-ledger" style={{ marginBottom: 'var(--space-sm)' }}>
            <strong>도시 원장{valid ? ` · ${cityId}번 도시` : ''}</strong>
            {!valid && <p>도시 ID를 입력하면 그 도시의 금·병량·도시병사를 보여줍니다.</p>}
            {valid && error && <p role="status" style={{ color: 'crimson' }}>{error}</p>}
            {valid && !error && (
                <table>
                    <tbody>
                        <tr>
                            <th scope="row">금</th>
                            <td data-testid="v2-ledger-gold">{ledger ? formatLedgerNumber(ledger.gold) : '…'}</td>
                            <th scope="row">병량</th>
                            <td data-testid="v2-ledger-rice">{ledger ? formatLedgerNumber(ledger.rice) : '…'}</td>
                            <th scope="row">도시병사</th>
                            <td data-testid="v2-ledger-garrison">
                                {ledger ? formatLedgerNumber(ledger.garrison) : '…'}
                            </td>
                        </tr>
                    </tbody>
                </table>
            )}
            {valid && loading && <span aria-hidden>갱신 중…</span>}
        </div>
    );
}
