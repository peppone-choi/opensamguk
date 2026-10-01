'use client';

// 도움말 본문(P-A01) — 서랍(데스크톱 400 · 태블릿 360) · 시트(모바일 724) · 독립 페이지가 같은 본문을 쓴다.
// 서랍 · 시트 껍데기와 열고 닫기, 주소 `?help=` 는 셸(K3)이 맡는다. 이 부품은 보기 하나를 그리고 보기 바꾸기를 알린다.
// 모달이 아니다 — 포커스를 가두지 않는다. 열리면 검색칸에 포커스, Esc 로 닫기(onClose).
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { HelpView } from '@/lib/help-route';
import type { HelpScreen } from '@/lib/help-screens';
import { FirstSteps } from './FirstSteps';
import { Icon } from './HelpBits';
import { HelpBrowse, HelpHome, HelpSearchResults } from './HelpLists';
import { FailureView, InputTopicView, PlainTopicView } from './HelpTopicView';
import s from './Help.module.css';

export interface HelpPanelProps {
    readonly view: HelpView;
    /** 보기를 바꾼다. `replace` = 찾기어처럼 주소 내역을 쌓지 않는 변경. */
    readonly onNavigate: (view: HelpView, mode?: 'push' | 'replace') => void;
    /** 앞 보기로(주소 내역 뒤로). 없으면 뒤로 단추를 숨긴다. */
    readonly onBack?: () => void;
    readonly onClose?: () => void;
    /** 「이 화면」 기준 화면. */
    readonly screen: HelpScreen;
    /** 「이 명령 하러 가기」 — 결정 화면으로 이 입력을 고른 채. */
    readonly goToInput?: (inputId: string) => void;
    readonly variant: 'drawer' | 'sheet' | 'page';
    /** 열릴 때 검색칸에 포커스(서랍 · 시트). */
    readonly autoFocus?: boolean;
}

const TABS: readonly { key: 'home' | 'browse' | 'start'; label: string }[] = [
    { key: 'home', label: '이 화면' },
    { key: 'browse', label: '분류' },
    { key: 'start', label: '첫걸음' },
];

export function HelpPanel(props: HelpPanelProps) {
    const { view, onNavigate, onBack, onClose, screen, variant } = props;
    const [raw, setRaw] = useState(view.kind === 'search' ? view.q : '');
    const [composing, setComposing] = useState(false);
    const input = useRef<HTMLInputElement>(null);

    useEffect(() => {
        if (props.autoFocus) input.current?.focus();
    }, [props.autoFocus]);

    // 주소가 바뀌어 찾기에서 벗어나면 칸을 비운다(뒤로 가기).
    useEffect(() => {
        if (view.kind !== 'search') setRaw('');
        else setRaw((r) => (r.trim() === view.q.trim() ? r : view.q));
    }, [view]);

    const searching = raw.trim().length > 0;
    const onChange = (value: string) => {
        setRaw(value);
        if (!composing) syncSearch(value);
    };
    const syncSearch = (value: string) => {
        const q = value.trim();
        if (q.length >= 2) onNavigate({ kind: 'search', q }, view.kind === 'search' ? 'replace' : 'push');
        else if (q.length === 0 && view.kind === 'search') onNavigate({ kind: 'home' }, 'replace');
    };
    const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
        if (e.key === 'Escape' && onClose) {
            e.stopPropagation();
            onClose();
        }
        if (e.key === '/' && document.activeElement !== input.current && !(e.target instanceof HTMLInputElement)) {
            e.preventDefault();
            input.current?.focus();
        }
    };

    const tab = view.kind === 'home' || view.kind === 'browse' || view.kind === 'start' ? view.kind : null;

    return (
        // 서랍 · 시트는 셸의 <aside aria-label="도움말"> 안에 든다 — 같은 이름의 구역을 겹쳐 두지 않는다.
        <section className={s.panel} aria-label={variant === 'page' ? '도움말' : undefined} onKeyDown={onKeyDown} data-help-panel={variant}>
            <div className={[s.head, onBack ? s.hasBack : ''].join(' ')}>
                {onBack ? <button type="button" className={s.iconBtn} aria-label="앞 보기로" onClick={onBack}><Icon name="back" /></button> : null}
                <h2 className={s.title}>도움말</h2>
                {onClose ? (
                    <span className={s.headEnd}><button type="button" className={s.iconBtn} aria-label="도움말 닫기(Esc)" onClick={onClose}><Icon name="close" /></button></span>
                ) : null}
            </div>
            {view.kind !== 'start' ? (
                <div className={s.searchRow} role="search">
                    <label className={s.search}>
                        <span className={s.searchLabel}>도움말 찾기</span>
                        <Icon name="search" />
                        <input ref={input} type="search" value={raw} placeholder="도움말 찾기 — 두 글자 이상" maxLength={80}
                            onChange={(e) => onChange(e.target.value)}
                            onCompositionStart={() => setComposing(true)}
                            onCompositionEnd={(e) => { setComposing(false); syncSearch(e.currentTarget.value); }} />
                    </label>
                </div>
            ) : null}
            {tab && !searching ? (
                <div className={s.tabs}>
                    <div role="tablist" aria-label="도움말 보기" className={s.seg}>
                        {TABS.map((t) => (
                            <button key={t.key} type="button" role="tab" aria-selected={t.key === tab} onClick={() => onNavigate({ kind: t.key })}>{t.label}</button>
                        ))}
                    </div>
                </div>
            ) : null}
            <div className={s.body}>
                {searching ? <HelpSearchResults raw={raw} composing={composing} onNavigate={onNavigate} />
                    : view.kind === 'home' ? <HelpHome screen={screen} onNavigate={onNavigate} />
                        : view.kind === 'browse' ? <HelpBrowse onNavigate={onNavigate} />
                            : view.kind === 'input' ? <InputTopicView inputId={view.inputId} highlight={view.reason} onNavigate={onNavigate} goToInput={props.goToInput} />
                                : view.kind === 'topic' ? <PlainTopicView topicId={view.topicId} onNavigate={onNavigate} />
                                    : view.kind === 'failure' ? <FailureView reason={view.reason} inputId={view.inputId} onNavigate={onNavigate} />
                                        : view.kind === 'start' ? <FirstSteps /> : null}
            </div>
        </section>
    );
}
