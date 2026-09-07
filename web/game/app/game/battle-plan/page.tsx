'use client';
// Phase 4X-C 09 「명령 봉인」 — /game/battle-plan?city={id} (군사 부서 아래 새 화면, 기존 라벨 변경 없음).
import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import BattlePlanPanel from '../../../components/game/BattlePlanPanel';

function Body() {
    const params = useSearchParams();
    const cityId = Number(params.get('city'));
    return (
        <div className="page-content">
            <PageHead title="명령 봉인" />
            {Number.isFinite(cityId) && cityId > 0 ? (
                <BattlePlanPanel cityId={cityId} battleCenterHref="battle-center" />
            ) : (
                <p className="text-muted">목표 도시가 없습니다 — 작전실 명령 목록의 출병 예약에서 「봉인」 으로 들어오세요.</p>
            )}
        </div>
    );
}

export default function BattlePlanPage() {
    return (
        <Shell>
            <Suspense fallback={<div className="page-content"><PageHead title="명령 봉인" /><p className="text-muted">로딩 중...</p></div>}>
                <Body />
            </Suspense>
        </Shell>
    );
}
