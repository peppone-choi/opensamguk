'use client';

// 본관 현 고르기(P-E02 본관 칸, 설계 §2.3) — 서버 후보(K5-02: 주 · 군 거르기 · 현 찾기 · 불가 사유)를 목록으로,
// 새 지도(교체 스위치 + 서버 bakeId)가 있으면 지도 표지(CountyMap)로도 고른다. 둘은 같은 picker 를 쓴다.
// 지도 원천(useCreationMap)은 Editor 가 한 번 읽어 넘긴다 — 모바일 걸음을 오가며 이 칸이 다시 마운트돼도 미리보기를 다시 읽지 않는다.
// 지도 칸이 없는 현(cell 없음 · available=false — 성 없음, 타일 城 id 불일치 등)은 지도에 표지가 없고 목록에 「못 고름 + 사유」로만 나온다.
// 가짜 좌표나 다른 城 칸으로 대신하지 않는다.
import { useEffect, useMemo, useState } from 'react';
import { StatusView, TargetCandidateList, useTargetPicker } from '@opensamguk/ui';
import type { CreationMap } from '@/hooks/useCreationMap';
import type { GeneralCreationOptions } from '@/lib/creation-contract';
import { commanderiesOf, countiesCentre, countyCandidate, countyCell, filterCounties, provincesOf } from '@/lib/create-view';
import CountyMap from './CountyMap';
import styles from './creation.module.css';

export default function CountyPick({ options, map, countyId, setCountyId }: {
    readonly options: GeneralCreationOptions; readonly map: CreationMap; readonly countyId: number | null; readonly setCountyId: (id: number | null) => void;
}) {
    const counties = options.nativeCounties;
    const [province, setProvince] = useState<string | null>(null);
    const [commandery, setCommandery] = useState<string | null>(null);
    const [q, setQ] = useState('');
    const shown = useMemo(() => filterCounties(counties, { province, commandery, q }), [counties, province, commandery, q]);
    const candidates = useMemo(() => shown.map((c) => countyCandidate(c, c.cityId === countyId)), [shown, countyId]);
    const picker = useTargetPicker({ kind: 'place', candidates, onCancel: () => setCountyId(null), initialSelected: countyId === null ? [] : [String(countyId)] });
    const picked = picker.selected[0];
    // 지도를 옮길 칸 — 고른 현, 또는 방금 거른 주 · 군 후보의 가운데(지도가 화면 밖일 때만 옮긴다)
    const [focus, setFocus] = useState(() => countyCell(counties.find((c) => c.cityId === countyId)));
    // 고른 값을 위로 올린다. setCountyId 는 Editor 가 그릴 때마다 새 함수라 이 효과는 이름 · 능력 입력에도 돈다 — 멱등이어야 한다.
    useEffect(() => {
        if (picked === undefined) return;
        const id = Number(picked);
        if (id !== countyId) setCountyId(id);
    }, [picked, countyId, setCountyId]);
    // 지도는 고른 현이 바뀔 때만 그 현으로 — 위 효과에 묶으면 상관없는 입력에 거른 주 · 군 화면이 풀린다(#1348 리뷰).
    useEffect(() => {
        if (picked === undefined) return;
        const id = Number(picked);
        setFocus(countyCell(counties.find((c) => c.cityId === id)));
    }, [picked, counties]);
    const refocus = (next: { province: string | null; commandery: string | null }) => {
        if (next.province === null && next.commandery === null) return;
        setFocus(countiesCentre(filterCounties(counties, { ...next, q: '' })));
    };
    return (
        <div className={styles.countyPick}>
            {/* 설계 §2.3 순서: 지도 → 주 · 군 선택 → 현 목록 */}
            {map.kind === 'ready'
                ? <CountyMap source={map.source} preview={map.preview} candidates={candidates} picker={picker} focus={focus} selectedCityId={countyId} />
                : null}
            <div className={styles.countyFilters}>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>주</span>
                    <select className="os-input" value={province ?? ''} onChange={(e) => { const next = e.target.value || null; setProvince(next); setCommandery(null); refocus({ province: next, commandery: null }); }}>
                        <option value="">전체</option>
                        {provincesOf(counties).map((p) => <option key={p} value={p}>{p}</option>)}
                    </select>
                </label>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>군 · 국</span>
                    <select className="os-input" value={commandery ?? ''} onChange={(e) => { const next = e.target.value || null; setCommandery(next); refocus({ province, commandery: next }); }}>
                        <option value="">전체</option>
                        {commanderiesOf(counties, province).map((c) => <option key={c} value={c}>{c}</option>)}
                    </select>
                </label>
                <label className={styles.field}>
                    <span className={styles.fieldLabel}>현 찾기</span>
                    <input type="search" className="os-input" placeholder="현 이름" value={q} onChange={(e) => setQ(e.target.value)} />
                </label>
            </div>
            <p className={styles.wait}>{map.kind === 'none' ? '지도 없이 목록에서 고릅니다. ' : ''}성이 있는 현만 고를 수 있습니다.</p>
            {candidates.length === 0
                ? <StatusView kind="empty" title="맞는 현이 없습니다" body="거르기를 바꾸거나 현 이름을 줄여 보세요." />
                : <TargetCandidateList picker={picker} candidates={candidates} label="본관 현 후보" />}
        </div>
    );
}
