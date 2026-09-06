'use client';

// 옛 경로 유지(테스트·북마크 호환). 본문은 components/admin/GeneralModerationPanel 로 옮겼고 허브(/game/admin?tab=…)가 같은 패널을 그린다.
import Shell from '@/components/Shell';
import GeneralModerationPanel from '@/components/admin/GeneralModerationPanel';

export default function Admin2Page() {
    return (
        <Shell>
            <GeneralModerationPanel />
        </Shell>
    );
}
