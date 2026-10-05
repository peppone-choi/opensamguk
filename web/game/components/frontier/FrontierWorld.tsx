'use client';

// 외교 › 주변 세계 탭 — 주변 세계 읽기(K8-09)를 받아 FrontierTab 에 넘긴다. 화면 그림은 FrontierTab(load), 읽기는 useFrontier 훅.
import { useGameSession } from '@/lib/campaign-session';
import { useFrontier } from '@/lib/use-frontier';
import { FrontierTab } from './FrontierTab';

export function FrontierWorld() {
    const { generalId } = useGameSession();
    return <FrontierTab load={useFrontier(generalId)} />;
}
