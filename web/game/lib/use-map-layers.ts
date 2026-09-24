'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { CommanderyVisibility } from '@opensamguk/ui';
import { api, isIntakeDenied, isIntakeQueued } from './api';
import type { HwihaCorps, HwihaScoutOption, HwihaSieges, HwihaWorks } from './hwiha-reads';

export type MapLayerScope = 'full' | 'fog' | 'none';

export function useMapLayers(scope: MapLayerScope, refreshKey: unknown) {
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [visibility, setVisibility] = useState<ReadonlyMap<number, CommanderyVisibility> | null>(null);
    const [intelAge, setIntelAge] = useState<ReadonlyMap<number, number>>(new Map());
    const [corps, setCorps] = useState<readonly HwihaCorps[]>([]);
    const [works, setWorks] = useState<HwihaWorks | null>(null);
    const [sieges, setSieges] = useState<HwihaSieges | null>(null);
    const [scoutOptions, setScoutOptions] = useState<readonly HwihaScoutOption[]>([]);
    const [visibilityError, setVisibilityError] = useState(false);
    const [corpsError, setCorpsError] = useState(false);
    const [badgeError, setBadgeError] = useState(false);
    const [scoutError, setScoutError] = useState(false);
    const [scoutPending, setScoutPending] = useState(false);
    const [scoutMessage, setScoutMessage] = useState<string | null>(null);
    const [revision, setRevision] = useState(0);

    useEffect(() => {
        if (scope === 'none') {
            setGeneralId(null); setVisibility(null); setCorps([]); setScoutOptions([]); setWorks(null); setSieges(null);
            setVisibilityError(false); setCorpsError(false); setBadgeError(false); setScoutError(false);
            return;
        }
        const controller = new AbortController();
        setVisibility(null); setCorps([]); setScoutOptions([]); setWorks(null); setSieges(null);
        setVisibilityError(false); setCorpsError(false); setBadgeError(false); setScoutError(false);
        void api.frontInfo(controller.signal).then(async (front) => {
            if (controller.signal.aborted) return;
            const id = front.general.generalId;
            setGeneralId(id);
            if (id == null) return;
            void api.hwihaVisibility(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status !== 'READY' || !result.commanderies) { setVisibilityError(true); return; }
                setVisibility(new Map(result.commanderies.map((entry) => [entry.no, entry.tier])));
                setIntelAge(new Map(result.commanderies.filter((entry) => entry.ageTurns != null)
                    .map((entry) => [entry.no, entry.ageTurns!])));
            }).catch(() => { if (!controller.signal.aborted) setVisibilityError(true); });
            void api.hwihaWorks(id, controller.signal).then((result) => {
                if (!controller.signal.aborted) { setWorks(result.status === 'READY' ? result : null); if (result.status !== 'READY') setBadgeError(true); }
            }).catch(() => { if (!controller.signal.aborted) { setWorks(null); setBadgeError(true); } });
            void api.hwihaSieges(id, controller.signal).then((result) => {
                if (!controller.signal.aborted) { setSieges(result.status === 'READY' ? result : null); if (result.status !== 'READY') setBadgeError(true); }
            }).catch(() => { if (!controller.signal.aborted) { setSieges(null); setBadgeError(true); } });
            if (scope !== 'full') return;
            void api.hwihaCorps(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status !== 'READY') { setCorpsError(true); return; }
                setCorps(result.corps ?? []);
            }).catch(() => { if (!controller.signal.aborted) setCorpsError(true); });
            void api.hwihaScoutOptions(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status === 'READY') setScoutOptions(result.options ?? []);
                else setScoutError(true);
            }).catch(() => { if (!controller.signal.aborted) { setScoutOptions([]); setScoutError(true); } });
        }).catch(() => {
            if (!controller.signal.aborted) { setGeneralId(null); setVisibilityError(true); setBadgeError(true); if (scope === 'full') { setCorpsError(true); setScoutError(true); } }
        });
        return () => controller.abort();
    }, [scope, refreshKey, revision]);

    const scoutable = useMemo(() => new Set(scoutOptions.filter((entry) => entry.available).map((entry) => entry.no)), [scoutOptions]);
    const sendScout = useCallback(async (no: number) => {
        const option = scoutOptions.find((entry) => entry.no === no && entry.available);
        if (generalId == null || !option || scoutPending) return;
        setScoutPending(true);
        try {
            const reserved = await api.reservedCommands(generalId);
            const used = new Set(reserved.slots.map((slot) => slot.turnIdx));
            const turnIdx = Array.from({ length: 12 }, (_, index) => index).find((index) => !used.has(index));
            if (turnIdx === undefined) { setScoutMessage('명령 목록 12순이 모두 찼습니다.'); return; }
            const result = await api.command('action.scout', { commanderyId: option.id }, generalId, turnIdx);
            if (isIntakeQueued(result)) {
                setScoutMessage(`${option.name}에 첩보를 ${turnIdx + 1}순에 예약했습니다.`);
                setRevision((value) => value + 1);
            } else if (isIntakeDenied(result)) setScoutMessage(result.reason ?? '첩보를 예약할 수 없습니다.');
        } catch (error) {
            setScoutMessage(error instanceof Error ? error.message : '첩보를 예약하지 못했습니다.');
        } finally { setScoutPending(false); }
    }, [generalId, scoutOptions, scoutPending]);
    return { visibility, intelAge, corps, works, sieges, scoutable, sendScout, scoutPending, scoutMessage,
        visibilityError, corpsError, badgeError, scoutError, canScout: scope === 'full' && generalId != null && !scoutError };
}
