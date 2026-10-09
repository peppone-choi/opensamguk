'use client';

import { useId, useMemo, useState } from 'react';
import { ReasonTooltip, type TargetPicker } from '@opensamguk/ui';
import type { Candidate } from '@/lib/command-flow/options';
import { destinationChoices, type DestinationGroup, type DestinationSort } from '@/lib/command-flow/destination-choices-view';
import styles from './DestinationChoices.module.css';

export default function DestinationChoices({ candidates, picker, label }: {
    readonly candidates: readonly Candidate[];
    readonly picker: TargetPicker;
    readonly label: string;
}) {
    const id = useId();
    const [query, setQuery] = useState('');
    const [group, setGroup] = useState<DestinationGroup>('nearby');
    const [sort, setSort] = useState<DestinationSort>('distance');
    const [onlyAvailable, setOnlyAvailable] = useState(true);
    const [visible, setVisible] = useState(50);
    const searchingNearby = group === 'nearby' && query.trim().length > 0;
    const { shown, distanceMissing } = useMemo(() => {
        const pool = onlyAvailable ? candidates.filter(c => c.available) : candidates;
        // Search the global prechecked pool; the default nearby display is never an admission limit.
        const rows = destinationChoices(pool, query, searchingNearby ? 'all' : group, sort);
        const missing = group === 'nearby' && !query.trim() && rows.length === 0 && pool.length > 0;
        return { shown: missing ? destinationChoices(pool, '', 'all', sort).slice(0, 20) : rows, distanceMissing: missing };
    }, [candidates, query, group, sort, onlyAvailable, searchingNearby]);
    const selected = candidates.find(c => c.value === picker.selected[0]);
    return <div className={styles.destinations}>
        <div className={styles.controls}>
            <label htmlFor={`${id}-search`}>목적지 검색
                <input id={`${id}-search`} type="search" placeholder="지명 또는 구역 번호" value={query} onChange={e => { setQuery(e.target.value); setVisible(50); }}
                    onKeyDown={e => {
                        if (e.key === 'Escape' && query && !e.nativeEvent.isComposing && e.nativeEvent.keyCode !== 229) {
                            e.preventDefault(); setQuery(''); setVisible(50);
                        }
                    }} />
            </label>
            <label htmlFor={`${id}-group`}>목적지 범위
                <select id={`${id}-group`} value={group} onChange={e => { setGroup(e.target.value as DestinationGroup); setVisible(50); }}>
                    <option value="all">모든 목적지</option><option value="nearby">가까운 20곳</option>
                    <option value="this-turn">이번 턴 도착</option><option value="multi-turn">다턴 이동</option>
                </select>
            </label>
            <label htmlFor={`${id}-sort`}>목적지 정렬
                <select id={`${id}-sort`} value={sort} onChange={e => { setSort(e.target.value as DestinationSort); setVisible(50); }}>
                    <option value="distance">거리순</option><option value="turns">도착 예상순</option><option value="name">이름순</option>
                </select>
            </label>
        </div>
        <label className={`os-check ${styles.available}`}><input type="checkbox" checked={onlyAvailable} onChange={e => { setOnlyAvailable(e.target.checked); setVisible(50); }} /><span>가능만</span></label>
        <p className={styles.count} role="status">검색·범위 결과 {shown.length.toLocaleString('ko-KR')} / {candidates.length.toLocaleString('ko-KR')}곳</p>
        {group === 'nearby' ? <p className={styles.count}>{searchingNearby
            ? '검색은 전체 목적지에서 찾습니다. 가능만을 끄면 불가 목적지와 사유도 볼 수 있습니다.'
            : distanceMissing ? '목적지 거리를 확인하지 못해 20곳까지 먼저 표시합니다. 가까운 곳이나 도착 순을 추정하지 않습니다.'
                : '서버 경로 거리 기준 가까운 20곳입니다. 먼 곳은 검색하거나 모든 목적지에서 볼 수 있습니다.'}</p> : null}
        <div role="listbox" aria-label={label} className="os-cands__list">
            {shown.length === 0 ? <p className="os-cands__none">조건에 맞는 목적지가 없습니다.</p> : null}
            {shown.slice(0, visible).map(c => <DestinationRow key={c.value} candidate={c} picker={picker} />)}
        </div>
        {shown.length > visible ? <button type="button" className="os-button os-button--ghost" onClick={() => setVisible(n => n + 50)}>목적지 더 보기 ({shown.length - visible}곳 남음)</button> : null}
        {selected && !selected.available ? <p role="alert">지금은 이 목적지를 예약할 수 없습니다: {selected.reason?.trim() || '사유를 확인하지 못했습니다'}</p> : null}
        {selected ? <details className={styles.selected}>
            <summary>고른 목적지: {selected.label} — 도착 예상·비용·거리 보기</summary>
            <p>{selected.detail}</p>
        </details> : null}
        {!selected && picker.selected[0] ? <p role="alert">고른 구역 {picker.selected[0]}의 현재 목적지 정보를 확인할 수 없습니다. 목적지 정보를 다시 확인한 뒤 선택해 주세요.</p> : null}
    </div>;
}

function DestinationRow({ candidate: c, picker }: { readonly candidate: Candidate; readonly picker: TargetPicker }) {
    const text = <><span className={`os-opt__dot os-opt__dot--${c.available ? 'ok' : 'no'}`} aria-hidden="true" />
        <span className="os-opt__text"><span className="os-opt__name">{c.label}</span>
            <span className="os-opt__sub"><span className="os-opt__sub-text">{c.summary ?? c.detail}</span></span></span></>;
    if (!c.available) {
        const reason = c.reason?.trim() || '사유를 받지 못했습니다';
        return <ReasonTooltip reason={reason} title={`${c.label} — 고를 수 없습니다`} block>
            {describedBy => <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy}
                className="os-opt os-opt--no" data-target-id={c.value}>
                {text}<span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
            </button>}
        </ReasonTooltip>;
    }
    const selected = picker.selected.includes(c.value);
    return <button type="button" role="option" aria-selected={selected} className={`os-opt${selected ? ' os-opt--sel' : ''}`}
        data-target-id={c.value} onClick={() => picker.pick(c.value)}>
        {text}<span className="os-opt__end"><span className={`os-chip os-chip--${selected ? 'bronze' : 'moss'}`}>{selected ? '고름' : '가능'}</span></span>
    </button>;
}
