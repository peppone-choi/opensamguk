'use client';
// 계정 설정 · 대표 장수(ADR-LITE-049 13) — 후보는 계정이 가진 플레이어 장수(세계별). 저장 결과는 서버 응답으로만 갱신한다.
import React, { type FormEvent, useEffect, useState } from 'react';
import { fetchRepresentative, setRepresentative, type RepresentativeResponse } from '@/lib/representative';

export default function RepresentativeSection() {
    const [data, setData] = useState<RepresentativeResponse | null>(null);
    const [draft, setDraft] = useState<string>('');
    const [error, setError] = useState<string | null>(null);
    const [note, setNote] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        let active = true;
        fetchRepresentative()
            .then((next) => {
                if (!active) return;
                setData(next);
                setDraft(next.current.generalId != null ? String(next.current.generalId) : '');
            })
            .catch((e) => { if (active) setError(e instanceof Error ? e.message : '대표 장수를 불러오지 못했습니다.'); });
        return () => { active = false; };
    }, []);

    const submit = async (event: FormEvent) => {
        event.preventDefault();
        setBusy(true);
        setError(null);
        setNote(null);
        try {
            const next = await setRepresentative(draft === '' ? null : Number(draft));
            setData(next);
            setNote(next.current.name ? `대표 장수를 ${next.current.name}(으)로 저장했습니다.` : '대표 장수를 해제했습니다.');
        } catch (e) {
            setError(e instanceof Error ? e.message : '대표 장수를 저장하지 못했습니다.');
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="game-panel" id="representative">
            <h2>대표 장수</h2>
            <p className="text-muted" style={{ marginTop: 0 }}>커뮤니티 글·댓글에 붙는 서버 배지입니다. 내 계정이 가진 장수만 고를 수 있습니다.</p>
            {data && data.current.name && (
                <p role="status">현재 대표 장수: <b>{data.current.name}</b>{data.current.worldId != null ? ` · 월드 ${data.current.worldId}` : ''}</p>
            )}
            <form className="account-form" onSubmit={submit}>
                <label className="account-field">
                    대표 장수
                    <select aria-label="대표 장수" value={draft} onChange={(event) => setDraft(event.target.value)} disabled={data === null}>
                        <option value="">{data === null ? '불러오는 중…' : data.candidates.length === 0 ? '내 장수가 없습니다' : '없음'}</option>
                        {(data?.candidates ?? []).map((c) => (
                            <option key={c.generalId} value={c.generalId}>{c.name} · 월드 {c.worldId}{c.scenarioCode ? ` (${c.scenarioCode})` : ''}</option>
                        ))}
                    </select>
                </label>
                <button type="submit" disabled={busy || data === null}>대표 장수 저장</button>
            </form>
            {error ? <p role="alert">{error}</p> : null}
            {note ? <p role="status">{note}</p> : null}
        </section>
    );
}
