'use client';

import { TimeBar, type TimeBarEvent } from '@opensamguk/ui';
import { ReplayBoard } from '@/components/battle/ReplayBoard';
import { currentEvent, useReplayClock } from '@/hooks/useReplayClock';

// 합성 사건 — 보드 V31K5Replay 시간 막대의 예시 문장 · 시각(4:10 전투). 서버 값이 아니다.
const DURATION = 250_000;
const EVENTS: TimeBarEvent[] = [
    { id: 'start', at: 0, label: '개전', tone: 'info' },
    { id: 'clash', at: 42_000, label: '선봉끼리 부딪혔다', tone: 'bronze' },
    { id: 'charge', at: 72_000, label: '우리 선봉 허저가 돌격', tone: 'moss' },
    { id: 'duel', at: 92_000, label: '허저와 안량이 일기토', tone: 'rust' },
    { id: 'ai', at: 160_000, label: '하후돈 부곡을 AI가 맡았다', tone: 'info' },
    { id: 'retreat', at: 235_000, label: '상대가 후퇴했다', tone: 'moss' },
];

export default function ReplayLab({ boardId }: { readonly boardId: number }) {
    const clock = useReplayClock(DURATION);
    const now = currentEvent(EVENTS, clock.position);
    return (
        <main style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: 12, padding: 16, background: 'var(--bg)', color: 'var(--text)' }}>
            <strong>다시 보기 부품 시험 — 판 {boardId} · 합성 사건(서버 값 아님)</strong>
            <ReplayBoard boardId={boardId} terrainInputSha256={null} label={`전장 판 ${boardId}`} />
            <TimeBar
                mode="replay"
                nowText={now ? now.label : '아직 아무 일도 없습니다'}
                position={clock.position}
                duration={DURATION}
                events={EVENTS}
                onSeek={clock.seek}
                playing={clock.playing}
                onPlayPause={clock.togglePlay}
                speed={clock.speed}
                onSpeed={clock.setSpeed}
            />
            <output data-testid="replay-lab-clock">{`${Math.round(clock.position)} ${clock.playing ? 'playing' : 'paused'} ${clock.speed}`}</output>
        </main>
    );
}
