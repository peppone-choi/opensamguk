'use client';

// OPENSAM-155 (v2 R6) — 월드 전체 도시 원장 열람. 신규 라우트만 추가한다(기존 v1 페이지 무수정).
//
// 개별 도시 패널(components/v2/CityLedgerPanel)은 조작 화면 위에 얹히지만, "어느 도시에 무엇을 둘까"를 비교하려면
// 한 화면에서 전부 보이는 표가 필요하다(설계안 §8 — 보이지 않는 원장 위에서는 결정이 성립하지 않는다).

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import { fetchCityLedgerList, formatLedgerNumber, type CityLedgerView } from '../../../../lib/v2/cityLedger';

export default function V2CityLedgerPage() {
    const [entries, setEntries] = useState<CityLedgerView[] | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        void (async () => {
            try {
                setEntries((await fetchCityLedgerList()).entries);
            } catch (cause) {
                setError(cause instanceof Error ? cause.message : '도시 원장을 불러오지 못했습니다.');
            }
        })();
    }, []);

    return (
        <Shell>
            <div className="page-content">
                <h1>v2-lab · 도시 원장</h1>
                <GameCard>
                    {error && <p role="status" style={{ color: 'crimson' }}>{error}</p>}
                    {!error && entries === null && <p>불러오는 중…</p>}
                    {!error && entries !== null && entries.length === 0 && (
                        <p>원장에 기록된 도시가 아직 없습니다(모든 도시가 0/0/0).</p>
                    )}
                    {!error && entries !== null && entries.length > 0 && (
                        <table>
                            <thead>
                                <tr>
                                    <th scope="col">도시</th>
                                    <th scope="col">금</th>
                                    <th scope="col">병량</th>
                                    <th scope="col">도시병사</th>
                                </tr>
                            </thead>
                            <tbody>
                                {entries.map(entry => (
                                    <tr key={entry.cityId}>
                                        <td>{entry.cityId}</td>
                                        <td>{formatLedgerNumber(entry.gold)}</td>
                                        <td>{formatLedgerNumber(entry.rice)}</td>
                                        <td>{formatLedgerNumber(entry.garrison)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </GameCard>
            </div>
        </Shell>
    );
}
