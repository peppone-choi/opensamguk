'use client';

// 인자 패널 — 명령 머리(이름 · 순 칩) · 설명 · 칸들 · 미리 보기 · 제출.
// 가능 · 불가 · 준비 중은 서버 옵션으로만 그린다(InputAction). 빈 칸은 누르면 알린다(클라이언트가 막지 않는다).
import { InputAction, StatusView } from '@opensamguk/ui';
import type { FlowCommand } from '@/lib/command-flow/catalog';
import type { ArgValue, Draft } from '@/lib/command-flow/flow-state';
import { amountMax, type ArgField } from '@/lib/command-flow/options';
import { submitAvailability, type OptionsLoad } from '@/lib/command-flow/parts-adapter';
import type { TurnSlotView } from '@/lib/turn-slots';
import ArgFieldView from './ArgFields';
import styles from './CommandFlow.module.css';

export type { OptionsLoad };

export interface FlowResult {
    readonly kind: 'ok' | 'error' | 'info';
    readonly text: string;
}

export interface ArgsPanelProps {
    readonly command: FlowCommand | null;
    readonly slot: TurnSlotView;
    readonly options: OptionsLoad | undefined;
    readonly draft: Draft;
    readonly carried: readonly string[];
    readonly dropped: readonly string[];
    readonly missing: readonly string[];
    readonly submitting: boolean;
    readonly result: FlowResult | null;
    /** 마지막 제출을 서버가 거절했다 — 제출 단추가 그 code · reason으로 막히고 누르면 사유 시트. */
    readonly rejected: { readonly seq: number; readonly code?: string; readonly reason?: string } | null;
    readonly onArg: (key: string, value: ArgValue) => void;
    readonly onSubmit: () => void;
    readonly onBack: () => void;
    readonly onRetry: () => void;
    readonly onMapPick?: (field: ArgField) => void;
}

const slotNo = (turnIdx: number) => String(turnIdx + 1).padStart(2, '0');

export default function ArgsPanel(props: ArgsPanelProps) {
    const { command, slot, options, draft, carried, dropped, missing, submitting, result, rejected, onArg, onSubmit, onBack, onRetry, onMapPick } = props;
    if (!command) {
        return (
            <div className={styles.args}>
                <p className={styles.placeholder}>명령을 고르세요.</p>
            </div>
        );
    }
    const ready = options && options.state === 'READY' ? options : null;
    const fieldLabel = (key: string) => ready?.fields.find((f) => f.key === key)?.label ?? key;
    const availability = submitAvailability(command.inputId, options, rejected);

    return (
        <div className={styles.args}>
            <div className={styles.argsScroll}>
                <div className={styles.argsHead}>
                    <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={onBack}>← 명령 목록</button>
                    <h3 className={styles.argsName}>{command.name}</h3>
                    <span className="os-chip os-chip--bronze">{slotNo(slot.turnIdx)}순</span>
                </div>
                <p className={styles.blurb}>{command.blurb}</p>
                {ready?.place ? <p className={styles.note}>일어나는 곳: {ready.place}</p> : null}
                {carried.length > 0 ? (
                    <p className={styles.carried} role="status">앞 명령에서 고른 {carried.map(fieldLabel).join(' · ')}을 이어받았습니다.</p>
                ) : null}
                {dropped.length > 0 ? (
                    <p className={styles.dropped} role="status">이어받은 {dropped.map(fieldLabel).join(' · ')}은 이 명령에서 고를 수 없는 곳이라 비웠습니다.</p>
                ) : null}

                {options === undefined || options.state === 'loading' ? <StatusView kind="loading" rows={4} /> : null}
                {options?.state === 'error' ? (
                    <StatusView kind="error" title="이 명령의 선택지를 불러오지 못했습니다" body={options.message} onRetry={onRetry} />
                ) : null}
                {options?.state === 'UNREADABLE' ? (
                    <StatusView kind="error" title="이 명령의 선택지를 읽을 수 없습니다" body="서버가 이 명령의 선택지를 주지 않았습니다." errorCode={options.status} onRetry={onRetry} />
                ) : null}
                {options?.state === 'PLANNED' ? (
                    <div className={styles.waiting}>
                        <strong>아직 열리지 않은 명령입니다</strong>
                        <p style={{ margin: '4px 0 0' }}>열리면: {command.blurb}</p>
                    </div>
                ) : null}

                {ready?.fields.map((field) => (
                    <ArgFieldView
                        key={field.key}
                        field={field}
                        draft={draft}
                        inputId={command.inputId}
                        missing={missing.includes(field.key)}
                        onChange={onArg}
                        onMapPick={onMapPick}
                        amountMax={field.kind === 'amount' ? amountMax(ready, field, draft) : undefined}
                    />
                ))}

                {ready && ready.preview.length > 0 ? (
                    <dl className={styles.preview} aria-label="미리 보기">
                        {ready.preview.map((row) => (
                            <div key={row.label} style={{ display: 'contents' }}>
                                <dt>{row.label}</dt>
                                <dd>{previewText(row.now, row.after)}</dd>
                            </div>
                        ))}
                    </dl>
                ) : null}
            </div>

            <div className={styles.foot}>
                {result ? <p className={styles.result} data-kind={result.kind} role={result.kind === 'error' ? 'alert' : 'status'}>{result.text}</p> : null}
                <InputAction
                    key={rejected ? `rejected-${rejected.seq}` : 'submit'}
                    reasonDefaultOpen={rejected != null}
                    inputId={command.inputId}
                    availability={availability}
                    label={submitting ? '예약하는 중…' : `${slotNo(slot.turnIdx)}순에 예약`}
                    busy={submitting}
                    block
                    reasonTitle={rejected ? `${command.name} — 서버가 받지 않았습니다` : `${command.name} — 지금은 할 수 없습니다`}
                    onAct={onSubmit}
                    className={styles.submit}
                />
            </div>
        </div>
    );
}

function previewText(now: number | null, after: number | null): string {
    const f = (n: number) => n.toLocaleString('ko-KR');
    if (now != null && after != null) return `${f(now)} → ${f(after)}`;
    return f((after ?? now)!);
}
