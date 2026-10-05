'use client';

// Placement intake (placement.assign) — sends the sheet's body and turns the outcome into one notice line.
// Queued → ok line and onQueued (close the sheet, read again); denied → the server reason; network failure → retry line.

import { useCallback, useState } from 'react';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';

interface PlacementNotice {
    readonly tone: 'ok' | 'error';
    readonly text: string;
}

interface PlacementIntake {
    readonly busy: boolean;
    readonly notice: PlacementNotice | null;
    readonly clearNotice: () => void;
    readonly submit: (body: Readonly<Record<string, unknown>>) => Promise<void>;
}

export function usePlacementIntake(generalId: number | null, onQueued: () => void): PlacementIntake {
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<PlacementNotice | null>(null);
    const clearNotice = useCallback(() => setNotice(null), []);
    const submit = async (body: Readonly<Record<string, unknown>>) => {
        if (generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, 'placement', body);
            if (isIntakeQueued(out)) {
                setNotice({ tone: 'ok', text: '배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.' });
                onQueued();
            } else if (isIntakeDenied(out)) {
                setNotice({ tone: 'error', text: out.reason?.trim() || '배치를 받지 못했습니다.' });
            }
        } catch (e) {
            setNotice({ tone: 'error', text: e instanceof Error ? '배치를 보내지 못했습니다 — 다시 해 보세요.' : '배치를 보내지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };
    return { busy, notice, clearNotice, submit };
}
