'use client';

// 요청 카드 미리보기 — 위: 받은 요청 목록(실제 읽기 · 응답 경로, 장수 1번), 아래: 좁은 폭 · 응답 뒤 · 발 바꾸기 변형(정적 합성 자료).
import { useEffect, useState } from 'react';
import { IncomingRequests } from '@/components/requests/IncomingRequests';
import { RequestCard } from '@/components/requests/RequestCard';

const LAB_GENERAL_ID = 1;

export default function RequestLab() {
    const [hydrated, setHydrated] = useState(false);
    useEffect(() => setHydrated(true), []);
    return (
        <main data-hydrated={hydrated} style={{ maxWidth: 720, margin: '0 auto', padding: 16, display: 'grid', gap: 20, color: 'var(--text)' }}>
            <h1 style={{ margin: 0, fontSize: 20 }}>요청 카드 미리보기</h1>
            <section data-testid="lab-live" aria-labelledby="lab-live-h" style={{ display: 'grid', gap: 8 }}>
                <h2 id="lab-live-h" style={{ margin: 0, fontSize: 16 }}>받은 요청(읽기 · 응답)</h2>
                <IncomingRequests generalId={LAB_GENERAL_ID} />
            </section>
            <section data-testid="lab-compact" aria-labelledby="lab-compact-h" style={{ display: 'grid', gap: 8, maxWidth: 380 }}>
                <h2 id="lab-compact-h" style={{ margin: 0, fontSize: 16 }}>좁은 폭(서랍 380) · 응답 뒤 · 발 바꾸기</h2>
                <RequestCard
                    kind="발령"
                    from={{ name: '순욱' }}
                    what="허현(으)로 가라는 발령입니다"
                    due="200년 3월 하순까지 · 넘기면 수락"
                    consequence="충성과 명망이 줄어듭니다"
                    compact
                    state="accepted"
                />
                <RequestCard
                    kind="임명 제안"
                    from={{ name: '조조' }}
                    what="영천 태수 자리를 제안합니다"
                    foot={<p style={{ margin: 0, color: 'var(--muted)' }}>응답 입력은 서버 준비 중입니다</p>}
                />
            </section>
        </main>
    );
}
