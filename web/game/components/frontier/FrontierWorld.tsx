'use client';

// 외교 › 주변 세계 탭 — 주변 세계 읽기(K8-09)를 받아 FrontierTab 에 넘긴다. 화면 그림은 FrontierTab(load), 읽기는 useFrontier 훅.
// 세션을 다 읽었는데 장수가 없으면 읽을 장수가 없으니 뼈대에 머물지 않고 「자료 없음」(세션 다시 읽기)으로 둔다.
import { useGameSession } from '@/lib/campaign-session';
import { useFrontier } from '@/lib/use-frontier';
import { FrontierTab } from './FrontierTab';

export function FrontierWorld() {
    const { generalId, loading, refresh } = useGameSession();
    const load = useFrontier(generalId);
    return <FrontierTab load={generalId == null && !loading ? { state: 'unavailable', onReload: refresh } : load} />;
}
