'use client';

// 도움말 독립 페이지(P-A01) — 새 탭 · 알림 링크 · 모바일 전체 화면. 보기는 `?view=`(help-route 형식), 「이 화면」은 `?from=`.
// 서랍 · 시트(셸 `?help=`, components/shell/HelpDrawer)와 같은 본문(HelpPanel)을 넓게 그린다. 셸(머리줄 · 레일)은 /game 레이아웃의 GameFrame 이 준다.
import { Suspense, useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { HelpPanel } from '../../../components/help/HelpPanel';
import { formatHelpView, parseHelpView, type HelpView } from '../../../lib/help-route';
import { SCREEN_LABEL, type HelpScreen } from '../../../lib/help-screens';

function asScreen(value: string | null): HelpScreen {
    return value && value in SCREEN_LABEL ? (value as HelpScreen) : 'war-room';
}

function HelpPageBody() {
    const params = useSearchParams();
    const router = useRouter();
    const view: HelpView = parseHelpView(params.get('view')) ?? { kind: 'home' };
    const screen = asScreen(params.get('from'));
    const navigate = useCallback((next: HelpView, mode: 'push' | 'replace' = 'push') => {
        const q = new URLSearchParams(params.toString());
        q.set('view', formatHelpView(next));
        const url = `?${q.toString()}`;
        if (mode === 'replace') router.replace(url, { scroll: false });
        else router.push(url, { scroll: false });
    }, [params, router]);
    const atHome = view.kind === 'home';
    return (
        <div style={{ height: 'calc(100dvh - 120px)', minHeight: 560, maxWidth: 960, margin: '0 auto', border: '1px solid var(--line)' }}>
            {/* 본 서버 기준(첫걸음 진척은 연습 서버에서만 — 연습 서버 판별은 셸 세션 world.kind, 계약판 K7-03). */}
            <HelpPanel view={view} onNavigate={navigate} onBack={atHome ? undefined : () => router.back()} screen={screen}
                practice={false} variant="page" />
        </div>
    );
}

export default function HelpPage() {
    return (
        <Suspense fallback={null}>
            <HelpPageBody />
        </Suspense>
    );
}
