'use client';
// 계정 설정 · 대표 장수(ADR-LITE-049 13, 설계서 §2.5 RP1–RP6) — 후보는 계정이 가진 플레이어 장수(세계별). 저장 결과는 서버 응답으로만 갱신한다.
import React, { useCallback, useEffect, useState } from 'react';
import { Button, Panel, SectionHeader } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import { fetchRepresentative, setRepresentative, type RepresentativeResponse } from '@/lib/representative';

/** 후보 · 지금 값에 서버 이름 · 기수 · 시나리오 제목이 없다(계약판 K5-15) — 내부 월드 번호 · 시나리오 코드 원문 대신 이 줄을 보인다. */
const SERVER_PENDING = '서버 정보 준비 중';

type Load = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; data: RepresentativeResponse };

export default function RepresentativeSection() {
    const [load, setLoad] = useState<Load>({ kind: 'loading' });
    const [draft, setDraft] = useState<number | null>(null);
    const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
    const [busy, setBusy] = useState(false);

    const open = useCallback(() => {
        let active = true;
        setLoad({ kind: 'loading' });
        fetchRepresentative()
            .then((data) => {
                if (!active) return;
                setLoad({ kind: 'ready', data });
                setDraft(data.current.generalId);
            })
            .catch((e) => { if (active) setLoad({ kind: 'error', message: e instanceof Error ? e.message : '대표 장수를 불러오지 못했습니다.' }); });
        return () => { active = false; };
    }, []);
    useEffect(open, [open]);

    const save = async () => {
        setBusy(true);
        setResult(null);
        try {
            const data = await setRepresentative(draft);
            setLoad({ kind: 'ready', data });
            setDraft(data.current.generalId);
            setResult({ ok: true, text: data.current.name ? `대표 장수를 ${data.current.name}(으)로 저장했습니다.` : '대표 장수를 해제했습니다.' });
        } catch (e) {
            setResult({ ok: false, text: e instanceof Error ? e.message : '대표 장수를 저장하지 못했습니다.' });
        } finally {
            setBusy(false);
        }
    };

    const row = (value: number | null, name: string, sub: string) => (
        <button
            key={value ?? 'none'}
            type="button"
            role="option"
            aria-selected={draft === value}
            className={`os-opt gw31-account__opt${draft === value ? ' os-opt--sel' : ''}`}
            onClick={() => { if (!busy) setDraft(value); }}
        >
            <span className="os-opt__text">
                <span className="os-opt__name">{name}</span>
                <span className="os-opt__sub"><span className="os-opt__sub-text">{sub}</span></span>
            </span>
        </button>
    );

    return (
        <Panel className="gw31-account__panel" id="representative" aria-labelledby="account-representative">
            <SectionHeader as="h2" id="account-representative" title="대표 장수" />
            <div className="gw31-account__body">
                <p className="gw31-field__help">커뮤니티 글 · 댓글에 붙는 서버 배지입니다. 내 계정이 가진 장수만 고를 수 있습니다.</p>
                {load.kind === 'loading' && <StateLine kind="loading" title="대표 장수를 불러오는 중" />}
                {load.kind === 'error' && <StateLine kind="error" title="대표 장수를 불러오지 못했습니다" body={load.message} onRetry={open} />}
                {load.kind === 'ready' && load.data.candidates.length === 0 && (
                    <StateLine kind="empty" title="아직 만든 장수가 없습니다 — 로비에서 서버를 고르세요" />
                )}
                {load.kind === 'ready' && load.data.candidates.length > 0 && (
                    <>
                        {load.data.current.name && (
                            <p className="gw31-card__line">지금 대표 장수: <b>{load.data.current.name}</b> · {SERVER_PENDING}</p>
                        )}
                        <div role="listbox" aria-label="대표 장수" className="gw31-account__list">
                            {load.data.candidates.map((c) => row(c.generalId, c.name, SERVER_PENDING))}
                            {row(null, '없음', '배지를 달지 않는다')}
                        </div>
                        {busy
                            ? <Button disabled reason="처리 중입니다">대표 장수 저장</Button>
                            : <Button onClick={() => void save()}>대표 장수 저장</Button>}
                    </>
                )}
                {result && <p className={`gw31-account__result${result.ok ? '' : ' is-bad'}`} role={result.ok ? 'status' : 'alert'}>{result.text}</p>}
            </div>
        </Panel>
    );
}
