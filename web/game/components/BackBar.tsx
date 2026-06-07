'use client';

// BackBar — 서브 페이지 상단 돌아가기/갱신 바(레거시 TopBackBar.vue 패러티: 돌아가기 + 갱신).
// Shell이 감싸는 모든 서브 페이지에 공통 적용된다(메인 화면은 GameChrome이라 미적용 — 레거시도 TopBackBar는
// 서브 페이지 전용). 돌아가기는 히스토리 back, 없으면 메인(/game)으로 폴백. 갱신은 현재 페이지 새로고침.

import { useRouter } from 'next/navigation';

export default function BackBar() {
    const router = useRouter();

    const back = () => {
        // 히스토리가 있으면 뒤로, 없으면(직접 진입) 메인으로.
        if (typeof window !== 'undefined' && window.history.length > 1) router.back();
        else router.push('/game');
    };

    return (
        <div className="back-bar">
            <button type="button" className="back-bar-btn" onClick={back} aria-label="돌아가기">
                ← 돌아가기
            </button>
            <button type="button" className="back-bar-btn back-bar-reload" onClick={() => window.location.reload()} aria-label="갱신">
                ⟳ 갱신
            </button>
        </div>
    );
}
