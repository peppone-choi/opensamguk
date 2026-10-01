'use client';

// 도움말 띠 내용(K3 HelpStrip 자리 · K7 내용) — 결정 화면 인자 패널 제목 아래 한 줄.
// 설명 한 문장 · 「잘 되면 · 안 되면」 · 「도움말」 · 초안. 비용 · 조건은 넣지 않는다(결정 화면의 제출 전 미리보기가 보인다).
// 읽기에 실패하면 띠를 숨긴다 — 도움말 오류가 명령 제출을 막지 않는다.
import { useState } from 'react';
import { useHelpContext, useHelpTopic } from '@/hooks/useHelp';
import type { HelpTopic } from '@/lib/help';
import { helpText } from '@/lib/help-labels';
import { DraftChip, Icon } from './HelpBits';
import s from './Help.module.css';

function Strip({ topic, onOpenHelp }: { topic: HelpTopic; onOpenHelp?: () => void }) {
    const [open, setOpen] = useState(false);
    return (
        <div>
            <div className={s.strip} data-help-strip="">
                <Icon name="help" />
                <span>{helpText(topic.sections.explanation)}</span>
                <DraftChip state={topic.reviewState} />
                <button type="button" className={[s.btn, s.ghost].join(' ')} style={{ border: 0, color: 'var(--info)' }} aria-expanded={open}
                    onClick={() => setOpen((o) => !o)}>잘 되면 · 안 되면</button>
                {onOpenHelp ? (
                    <button type="button" className={s.iconBtn} aria-label="도움말 열기" onClick={onOpenHelp}><Icon name="next" /></button>
                ) : null}
            </div>
            {open ? (
                <div className={s.stripMore}>
                    <span><b style={{ color: 'var(--moss-2)' }}>잘 되면</b> {helpText(topic.sections.successExample)}</span>
                    <span><b style={{ color: 'var(--rust-2)' }}>안 되면</b> {helpText(topic.sections.failureExample)}</span>
                </div>
            ) : null}
        </div>
    );
}

/** 원장 입력의 결정 화면 — inputId 로 문맥 도움말을 읽는다. */
export function InputHelpStrip({ inputId, onOpenHelp }: { inputId: string; onOpenHelp?: () => void }) {
    const [load] = useHelpContext(inputId);
    return load.status === 'ready' ? <Strip topic={load.data.topic} onOpenHelp={onOpenHelp} /> : null;
}

/** 원장 행이 없는 결정 화면(장수 만들기 · 역사 인물 선택) — 주제 id 로 읽는다. 주제가 아직 없으면(404) 띠를 숨긴다. */
export function TopicHelpStrip({ topicId, onOpenHelp }: { topicId: string; onOpenHelp?: () => void }) {
    const [load] = useHelpTopic(topicId);
    return load.status === 'ready' ? <Strip topic={load.data.topic} onOpenHelp={onOpenHelp} /> : null;
}
