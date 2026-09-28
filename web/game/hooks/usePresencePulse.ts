'use client';

import { useEffect, useRef } from 'react';
import { api } from '@/lib/api';

/** Foreground owner activity only. SSE events and passive data refreshes never call this hook. */
export function usePresencePulse(enabled: boolean, phaseKey: string) {
    const lastSent = useRef<{ phase: string; at: number } | null>(null);
    const mountedForOwner = useRef(false);

    useEffect(() => {
        if (!enabled) {
            mountedForOwner.current = false;
            return;
        }
        let alive = true;
        const pulse = () => {
            if (!alive || document.visibilityState !== 'visible') return;
            const now = Date.now();
            if (lastSent.current?.phase === phaseKey && now - lastSent.current.at < 60_000) return;
            lastSent.current = { phase: phaseKey, at: now };
            void api.post<unknown>('/api/command/presence', {}).catch(() => {
                if (alive) lastSent.current = null;
            });
        };
        // An SSE-driven front-info refresh can change phaseKey while the user is idle.
        // Only the first visible mount and actual foreground interaction count as activity.
        if (!mountedForOwner.current) pulse();
        mountedForOwner.current = true;
        document.addEventListener('visibilitychange', pulse);
        window.addEventListener('focus', pulse);
        window.addEventListener('pointerdown', pulse, { passive: true });
        window.addEventListener('keydown', pulse);
        return () => {
            alive = false;
            document.removeEventListener('visibilitychange', pulse);
            window.removeEventListener('focus', pulse);
            window.removeEventListener('pointerdown', pulse);
            window.removeEventListener('keydown', pulse);
        };
    }, [enabled, phaseKey]);
}
