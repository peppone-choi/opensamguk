'use client';

// 옛 경로 유지(테스트·북마크 호환). 본문은 components/admin/NationStatsPanel 로 옮겼고 허브(/game/admin?tab=…)가 같은 패널을 그린다.
import Shell from '@/components/Shell';
import NationStatsPanel from '@/components/admin/NationStatsPanel';

export default function Admin5Page() {
    return (
        <Shell>
            <NationStatsPanel />
        </Shell>
    );
}
