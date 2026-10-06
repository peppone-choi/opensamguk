'use client';

// 서신 「도움 요청」 쓰기(D68) — 보드 V31K6HelpRequest(데스크톱 오른쪽 칸) · MHelpRequest(모바일), 원장 D111 승인.
// 글 서신과 따로인 정해진 양식이다: 받는 사람 · 종류(병력 · 자원) · 양 · 보낼 곳(구역) · 기한 · 본문(선택). 본문은 전달만 되고 명령으로 읽지 않는다.
// 서버 API · 입력 원장 행이 아직 없다(계약판 H01, 요구 §7). 그래서
// - 서버가 줄 값(받는 사람 후보 · 부곡 단위 · 자원 종류와 상한 · 보낼 곳 · 기한)은 「서버 대기」로 두고 기다리는 행을 data-server-wait 로 단다(#1335).
//   결정 대기 다섯 칸(받는 사람 범위 · 병력 단위 · 기한 · 판단 규칙 · 빈도)도 여기 든다.
// - 「도움 요청 보내기」는 입력 원장 행이 없어 「준비 중」이다. 지어낸 inputId · data-input-id 는 달지 않는다(보드 주석).
// 종류 고르기와 본문은 화면 안 일이라 지금도 움직인다(서버가 들어오면 같은 칸에 값이 들어온다).
import { useId, useState, type ReactNode } from 'react';
import { HELP_KIND_LABEL, HELP_KINDS, SERVER_WAIT, type HelpKind } from '@/lib/mail/help-request';
import { MAIL_TEXT_MAX } from '@/lib/mail/text';
import styles from './HelpRequest.module.css';

/** 본문 안내 — D68 「본문은 명령으로 읽지 않는다」(요구 §6.1 문구 그대로). */
export const HELP_BODY_NOTE = '본문은 전달만 됩니다 — 요청 내용은 위 양식으로만 판단합니다';

export function HelpRequestForm() {
    const [kind, setKind] = useState<HelpKind>('resource');
    const [body, setBody] = useState('');
    const bodyId = useId();
    const bodyNote = useId();
    const sendWhy = useId();

    return (
        <section className={styles.form} aria-label="도움 요청 쓰기" data-testid="help-request-form">
            <Field label="받는 사람" hint="고를 수 있는 사람은 서버가 준다">
                <Waiting row="H01 · 받는 사람" />
            </Field>
            <Field label="받는 사람 범위" hint="NPC 만인지, 사람 장수도 받는지">
                <Waiting row="H01 · 받는 사람 범위" />
            </Field>
            <Field label="종류">
                <div className={styles.seg} role="group" aria-label="도움 종류">
                    {HELP_KINDS.map((k) => (
                        <button key={k} type="button" className={styles.segButton} aria-pressed={kind === k} onClick={() => setKind(k)}>
                            {HELP_KIND_LABEL[k]}
                        </button>
                    ))}
                </div>
            </Field>
            {kind === 'troops' ? (
                <Field label="부곡" hint="병력은 부곡 단위로 청한다 — 숫자 병력은 없다">
                    <Waiting row="H01 · 병력 단위" />
                </Field>
            ) : (
                <Field label="자원 · 양" hint="한 번에 청할 수 있는 양의 상한은 서버 값">
                    <Waiting row="H01 · 자원 · 상한" />
                </Field>
            )}
            <Field label="보낼 곳(구역)">
                <Waiting row="H01 · 보낼 곳" />
            </Field>
            <Field label="기한">
                <Waiting row="H01 · 기한" />
            </Field>
            <Field label="받는 쪽 판단" hint="받는 장수는 자기 순에 정해진 규칙으로 판단한다 — 규칙 · 빈도는 구체안 뒤">
                <Waiting row="H01 · 판단 규칙 · 빈도" />
            </Field>
            <div className={styles.field}>
                <label htmlFor={bodyId} className={styles.label}>본문(선택)</label>
                <textarea id={bodyId} className={styles.body} value={body} maxLength={MAIL_TEXT_MAX} rows={4} aria-describedby={bodyNote}
                    onChange={(e) => setBody(e.target.value)} placeholder={`${MAIL_TEXT_MAX}자까지 · 서식 없이`} />
                <span id={bodyNote} className={styles.note}>{HELP_BODY_NOTE}</span>
            </div>
            <div className={styles.send}>
                <button type="button" className="os-button os-button--ghost os-button--block os-button--disabled" aria-disabled="true" aria-describedby={sendWhy}>
                    도움 요청 보내기
                </button>
                <span id={sendWhy} className={styles.why}>준비 중 — 입력 원장에 도움 요청 입력이 생기면 열립니다</span>
            </div>
        </section>
    );
}

function Field({ label, hint, children }: { readonly label: string; readonly hint?: string; readonly children: ReactNode }) {
    return (
        <div className={styles.field}>
            <span className={styles.label}>{label}</span>
            {children}
            {hint ? <span className={styles.hint}>{hint}</span> : null}
        </div>
    );
}

/** 서버 값이 오기 전 칸 — 기다리는 계약판 행(H01)을 data-server-wait 로 단다. */
function Waiting({ row }: { readonly row: string }) {
    return <span className={`os-chip os-chip--info ${styles.wait}`} data-server-wait={row}>{SERVER_WAIT}</span>;
}

export default HelpRequestForm;
