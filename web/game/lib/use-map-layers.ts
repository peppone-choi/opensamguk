'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CommanderyVisibility } from '@opensamguk/ui';
import { api } from './api';
import type { Corps, ScoutOption, Sieges, Works } from './campaign-reads';
import { reserveScout } from './campaign-scout';
import { readServerCookie } from './serverGameUrl';

export type MapLayerScope = 'full' | 'fog' | 'none';

export function useMapLayers(scope: MapLayerScope, refreshKey: unknown, suppliedGeneralId?: number | null) {
    const identity = useRef<{ server: string | undefined; scope: MapLayerScope; generalId: number | null } | null>(null);
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [visibility, setVisibility] = useState<ReadonlyMap<number, CommanderyVisibility> | null>(null);
    const [intelAge, setIntelAge] = useState<ReadonlyMap<number, number>>(new Map());
    const [corps, setCorps] = useState<readonly Corps[]>([]);
    const [works, setWorks] = useState<Works | null>(null);
    const [sieges, setSieges] = useState<Sieges | null>(null);
    const [scoutOptions, setScoutOptions] = useState<readonly ScoutOption[]>([]);
    const [visibilityError, setVisibilityError] = useState(false);
    const [corpsError, setCorpsError] = useState(false);
    const [badgeError, setBadgeError] = useState(false);
    const [scoutError, setScoutError] = useState(false);
    const [scoutPending, setScoutPending] = useState(false);
    const [scoutMessage, setScoutMessage] = useState<string | null>(null);
    const [revision, setRevision] = useState(0);

    useEffect(() => {
        const server = readServerCookie();
        const clearLayers = () => {
            setVisibility(null); setIntelAge(new Map()); setCorps([]); setScoutOptions([]);
            setWorks(null); setSieges(null);
        };
        if (scope === 'none') {
            identity.current = null;
            setGeneralId(null); clearLayers();
            setVisibilityError(false); setCorpsError(false); setBadgeError(false); setScoutError(false);
            return;
        }
        const controller = new AbortController();
        const previousIdentity = identity.current;
        if (!previousIdentity || previousIdentity.server !== server || previousIdentity.scope !== scope
            || (suppliedGeneralId !== undefined && previousIdentity.generalId !== suppliedGeneralId)) clearLayers();
        setVisibilityError(false); setCorpsError(false); setBadgeError(false); setScoutError(false);
        const resolveGeneralId = suppliedGeneralId !== undefined
            ? Promise.resolve(suppliedGeneralId)
            : api.frontInfo(controller.signal).then((front) => front.general.generalId);
        void resolveGeneralId.then((id) => {
            if (controller.signal.aborted) return;
            const activeIdentity = identity.current;
            if (!activeIdentity || activeIdentity.server !== server || activeIdentity.scope !== scope || activeIdentity.generalId !== id) clearLayers();
            identity.current = { server, scope, generalId: id };
            setGeneralId(id);
            if (id == null) return;
            void api.campaignVisibility(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status !== 'READY' || !result.commanderies) {
                    setVisibility(null); setIntelAge(new Map()); setCorps([]); setVisibilityError(true); return;
                }
                setVisibility(new Map(result.commanderies.map((entry) => [entry.no, entry.tier])));
                setIntelAge(new Map(result.commanderies.filter((entry) => entry.ageTurns != null)
                    .map((entry) => [entry.no, entry.ageTurns!])));
            }).catch(() => { if (!controller.signal.aborted) {
                setVisibility(null); setIntelAge(new Map()); setCorps([]); setVisibilityError(true);
            } });
            void api.campaignWorks(id, controller.signal).then((result) => {
                if (!controller.signal.aborted) { setWorks(result.status === 'READY' ? result : null); if (result.status !== 'READY') setBadgeError(true); }
            }).catch(() => { if (!controller.signal.aborted) { setWorks(null); setBadgeError(true); } });
            void api.campaignSieges(id, controller.signal).then((result) => {
                if (!controller.signal.aborted) { setSieges(result.status === 'READY' ? result : null); if (result.status !== 'READY') setBadgeError(true); }
            }).catch(() => { if (!controller.signal.aborted) { setSieges(null); setBadgeError(true); } });
            if (scope !== 'full') return;
            void api.campaignCorps(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status !== 'READY') { setCorps([]); setCorpsError(true); return; }
                setCorps(result.corps ?? []);
            }).catch(() => { if (!controller.signal.aborted) { setCorps([]); setCorpsError(true); } });
            void api.campaignScoutOptions(id, controller.signal).then((result) => {
                if (controller.signal.aborted) return;
                if (result.status === 'READY') setScoutOptions(result.options ?? []);
                else { setScoutOptions([]); setScoutError(true); }
            }).catch(() => { if (!controller.signal.aborted) { setScoutOptions([]); setScoutError(true); } });
        }).catch(() => {
            if (!controller.signal.aborted) { identity.current = null; setGeneralId(null); clearLayers();
                setVisibilityError(true); setBadgeError(true); if (scope === 'full') { setCorpsError(true); setScoutError(true); } }
        });
        return () => controller.abort();
    }, [scope, refreshKey, revision, suppliedGeneralId]);

    const scoutable = useMemo(() => new Set(scoutOptions.filter((entry) => entry.available).map((entry) => entry.no)), [scoutOptions]);
    const sendScout = useCallback(async (no: number) => {
        const option = scoutOptions.find((entry) => entry.no === no && entry.available);
        if (generalId == null || !option || scoutPending) return;
        setScoutPending(true);
        try {
            const result = await reserveScout(generalId, option);
            setScoutMessage(result.message);
            if (result.ok) setRevision((value) => value + 1);
        } finally { setScoutPending(false); }
    }, [generalId, scoutOptions, scoutPending]);
    return { visibility, intelAge, corps, works, sieges, scoutable, sendScout, scoutPending, scoutMessage,
        visibilityError, corpsError, badgeError, scoutError, canScout: scope === 'full' && generalId != null && !scoutError };
}
