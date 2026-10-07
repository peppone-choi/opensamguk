'use client';
import { useCallback, useEffect, useState } from 'react';
import { readServerVisibility, changeServerVisibility, type ServerVisibility } from '@/lib/api/server-visibility';

export function useServerVisibility(serverId: string) {
    const [current, setCurrent] = useState<ServerVisibility | null>(null);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState<string | null>(null);
    const load = useCallback(async () => {
        setBusy(true);
        try { setCurrent(await readServerVisibility(serverId)); setMessage(null); }
        catch { setCurrent(null); setMessage('공개 상태를 불러오지 못했습니다.'); }
        finally { setBusy(false); }
    }, [serverId]);
    useEffect(() => { void load(); }, [load]);
    async function toggle() {
        if (!current) return;
        setBusy(true); setMessage(null);
        try { setCurrent(await changeServerVisibility(serverId, current)); }
        catch (error) {
            setCurrent(null);
            setMessage(error instanceof Error ? error.message : '공개 상태를 변경하지 못했습니다.');
        } finally { setBusy(false); }
    }
    return { current, busy, message, load, toggle };
}
