'use client';

// '준비 중' stub — deferred MainControlBar targets (회의실/기밀실/부대편성/감찰부/유산관리) route here
// in F2 instead of a 404. The feature name comes from ?feature=. These become real routes in later waves.

import { useSearchParams } from 'next/navigation';
import { Suspense } from 'react';
import Shell from '@/components/Shell';

function ComingSoonBody() {
    const params = useSearchParams();
    const feature = params.get('feature') ?? '이 기능';

    return (
        <div className="page-content">
            <h1>{feature}</h1>
            <div className="error-state">
                <p className="text-muted">{feature}은(는) 준비 중입니다.</p>
                <p className="text-muted">다음 업데이트에서 제공될 예정입니다.</p>
            </div>
        </div>
    );
}

export default function ComingSoonPage() {
    return (
        <Shell>
            <Suspense fallback={<div className="page-content"><p className="text-muted">로딩 중...</p></div>}>
                <ComingSoonBody />
            </Suspense>
        </Shell>
    );
}
