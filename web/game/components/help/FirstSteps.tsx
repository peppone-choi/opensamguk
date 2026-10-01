'use client';

// 첫걸음(P-A02) — 8단계 설명만(사용자 결정 D21). 진행 기록 · 완료 표시 · 목표 표시는 없다.
// 단계마다 무엇을 · 어디서 · 어떻게, 아직 없는 것은 「준비 중」, 그리고 그 화면 바로가기(누를 영역 44).
import CampaignLink from '@/components/campaign/CampaignLink';
import { FIRST_STEPS, type FirstStep } from '@/lib/first-steps';
import { Chip, Icon } from './HelpBits';
import s from './Help.module.css';

function Go({ step }: { step: FirstStep }) {
    const className = [s.btn, s.ghost, s.wide].join(' ');
    const body = <>{step.go.label}<Icon name="next" /></>;
    return step.go.kind === 'game'
        ? <CampaignLink slug={step.go.slug} query={step.go.query} className={className} data-first-step-go={step.key}>{body}</CampaignLink>
        : <a href={step.go.href} className={className} data-first-step-go={step.key}>{body}</a>;
}

export function FirstSteps() {
    return (
        <div className={s.content}>
            <p className={s.note} style={{ margin: 0 }}>처음이라면 이 순서대로 해 보세요. 단계마다 그 화면으로 바로 갈 수 있습니다.</p>
            <ol className={s.steps} aria-label="첫걸음 8단계">
                {FIRST_STEPS.map((step) => (
                    <li key={step.key} className={s.step} data-first-step={step.key} data-first-step-id={step.explanationId} aria-labelledby={`first-step-${step.key}`}>
                        <div className={s.stepHead}>
                            <span className={s.stepNo} aria-hidden="true">{step.order}</span>
                            <h3 id={`first-step-${step.key}`}>{step.order}단계 · {step.name}</h3>
                            {step.pending ? <Chip tone="rust">일부 준비 중</Chip> : null}
                        </div>
                        <p className={s.text}>{step.what}</p>
                        <p className={s.where}><span className={s.label}>어디서</span>{step.where}</p>
                        <ol className={s.how} aria-label="어떻게">
                            {step.how.map((line, i) => <li key={line}><b>{i + 1}</b>{line}</li>)}
                        </ol>
                        {step.pending ? <span className={s.warn}>{step.pending}</span> : null}
                        <Go step={step} />
                    </li>
                ))}
            </ol>
        </div>
    );
}
