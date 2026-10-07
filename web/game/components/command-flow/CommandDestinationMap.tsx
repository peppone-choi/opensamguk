'use client';

import { useEffect, useMemo, useState } from 'react';
import { Modal, TargetCandidateList, useTargetPicker, type TargetCandidate } from '@opensamguk/ui';
import { MapTargetLayer, TopdownMap, loadBakePlaces, loadBakeProvinceCenters, topdownSourceFor, worldFromPreview,
    type Camera, type CellPoint, type PlacesData, type TopdownSource } from '@opensamguk/ui/map/topdown';
import { useCampaignWorldMap } from '@/lib/campaign-map';
import { destinationMapCandidates } from '@/lib/command-flow/destination-map-view';
import { toTargetCandidates } from '@/lib/command-flow/parts-adapter';
import type { ArgField } from '@/lib/command-flow/options';
import styles from './CommandDestinationMap.module.css';

interface Props {
    readonly field: ArgField;
    readonly refreshKey: number;
    readonly onConfirm: (value: string) => void;
    readonly onClose: () => void;
}

/** The map and list share the exact server candidates; map placement never decides order legality. */
export default function CommandDestinationMap({ field, refreshKey, onConfirm, onClose }: Props) {
    const map = useCampaignWorldMap(refreshKey);
    const preview = map.kind === 'preview' ? map.preview : null;
    const source = useMemo(() => topdownSourceFor(preview?.topdownBakeId), [preview?.topdownBakeId]);
    const [loaded, setLoaded] = useState<{ source: TopdownSource; centers: readonly (CellPoint | null)[]; places: PlacesData } | null>(null);
    const [failed, setFailed] = useState<TopdownSource | null>(null);
    const [camera, setCamera] = useState<Camera | null>(null);
    const [blocked, setBlocked] = useState<TargetCandidate | null>(null);
    useEffect(() => {
        if (!source) return undefined;
        let alive = true;
        setCamera(null);
        Promise.all([loadBakeProvinceCenters(source), loadBakePlaces(source)]).then(([centers, places]) => {
            if (alive) setLoaded({ source, centers, places });
        }).catch(() => { if (alive) setFailed(source); });
        return () => { alive = false; };
    }, [source]);
    const active = loaded?.source === source ? loaded : null;
    const candidates = useMemo(() => toTargetCandidates(field), [field]);
    const targets = useMemo(() => destinationMapCandidates(field, preview, active?.centers ?? null), [field, preview, active]);
    const picker = useTargetPicker({ kind: 'place', candidates, onCancel: onClose });
    const selectedId = picker.selected[0];
    const selected = candidates.find(c => c.targetId === selectedId && c.available);
    useEffect(() => { setBlocked(null); }, [selectedId]);
    const world = useMemo(() => preview && active ? worldFromPreview(preview, active.places.provinceCount) : null, [preview, active]);
    const cells = targets.flatMap(c => c.cell ? [c.cell] : []);
    const unavailable = map.kind === 'error' || map.kind === 'unsupported' || (source && failed === source);
    const confirm = () => { if (selected) onConfirm(selected.targetId); };

    return <Modal ariaLabel="목적지 지도에서 고르기" className={styles.dialog} onClose={onClose}>
        <header className={styles.head}>
            <h2>목적지 지도에서 고르기</h2>
            <button type="button" className="os-button os-button--ghost" onClick={onClose}>목록으로 돌아가기</button>
        </header>
        <p>이번 턴 도착과 다턴 이동을 구분합니다. 다턴 이동도 주문 가능한 목적지는 고를 수 있습니다.</p>
        <div className={styles.body}>
            <div className={styles.map} aria-label="목적지 도달 범위 지도">
                {source && active && cells.length > 0 ? <>
                    <TopdownMap source={source} world={world?.ok ? world.world : undefined} initialView={{ cells }}
                        onViewChange={view => setCamera(view.camera)} ariaLabel="서버 목적지 도달 범위"
                        style={{ width: '100%', height: '100%' }} />
                    <MapTargetLayer camera={camera} candidates={targets} picker={picker} dim={false} onBlocked={setBlocked} />
                </> : <p role="status">{unavailable ? '지도의 목적지 위치를 확인하지 못했습니다. 목록에서 고를 수 있습니다.'
                    : map.kind === 'loading' || (source && !active) ? '지도를 불러오는 중입니다.'
                    : '이 지도에서 목적지 위치를 확인할 수 없습니다. 목록에서 고를 수 있습니다.'}</p>}
            </div>
            <div className={styles.list}>
                <TargetCandidateList picker={picker} candidates={candidates} label={field.label} />
            </div>
        </div>
        {active && cells.length < targets.length ? <p role="status">지도 위치가 확인되지 않은 목적지는 목록에만 표시합니다.</p> : null}
        {blocked ? <p role="status">{blocked.name} — {blocked.reason ?? '사유를 받지 못했습니다'}</p> : null}
        <footer className={styles.foot}>
            {selected ? <><p>{selected.name} — {selected.sub}</p><button type="button" className="os-button" onClick={confirm}>이 목적지 선택</button></>
                : <p>지도 표지나 목록에서 목적지를 고르세요.</p>}
        </footer>
    </Modal>;
}
