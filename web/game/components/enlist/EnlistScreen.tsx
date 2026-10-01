'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { InputAction, PeoplePicker, Seg, StatusView, type InputAvailability, type PersonOption } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { InputHelpStrip } from '@/components/help/HelpStrip';
import { useReasonHelp } from '@/hooks/useHelp';
import { submitCommandAndAwaitResult, type CommandSubmitResult } from '@/lib/commandSubmit';
import type { EnlistmentOptionsResponse } from '@/lib/types';
import { EnlistHttpError, readEnlistOptions, sendEnlist, type EnlistOption } from './enlist-api';
import styles from './EnlistScreen.module.css';

const INPUT = 'action.enlist';
const GROUPS = [{ value: 'NATION', label: '세력' }, { value: 'GENERAL', label: '장수' }, { value: 'RANDOM', label: '무작위' }] as const;
const ALL = ['all'] as const;
type Mode = EnlistOption['mode'];
type Load = { kind: 'loading' } | { kind: 'ready'; data: EnlistmentOptionsResponse } | { kind: 'error'; error: Error };

/** E04 approved board; connected through the guarded K5 join route. */
export default function EnlistScreen({ generalId, onRefresh, onHelp }: {
  readonly generalId: number;
  readonly onRefresh: () => void;
  readonly onHelp: (topicId: string) => void;
}) {
  const generation = useRef(0);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [tick, setTick] = useState(0);
  const [mode, setMode] = useState<Mode>('NATION');
  const [picked, setPicked] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CommandSubmitResult | null>(null);
  const [submitError, setSubmitError] = useState<Error | null>(null);
  const retry = () => setTick(t => t + 1);
  useEffect(() => {
    const controller = new AbortController();
    generation.current += 1;
    setBusy(false);
    setLoad({ kind: 'loading' }); setPicked(null); setResult(null); setSubmitError(null);
    readEnlistOptions(generalId, controller.signal).then(data => {
      if (!controller.signal.aborted) setLoad({ kind: 'ready', data });
    }).catch(error => {
      if (!controller.signal.aborted) setLoad({ kind: 'error', error: error instanceof Error ? error : new Error('출사 정보를 불러오지 못했습니다.') });
    });
    return () => { controller.abort(); generation.current += 1; };
  }, [generalId, tick]);
  const data = load.kind === 'ready' ? load.data : null;
  const option = picked === null ? undefined : data?.options[picked];
  // Local array keys identify UI choices only. Always submit the original server targetId.
  const people = useMemo<readonly PersonOption[]>(() => (data?.options ?? []).flatMap((row, index) => row.mode === mode ? [{
    generalId: index, name: row.label, groups: [],
    ...(row.availability.status === 'BLOCKED' ? { blockedReason: row.availability.reason?.trim() || '사유를 받지 못했습니다' } : {}),
  }] : []), [data, mode]);
  const availability: InputAvailability = result?.status === 'rejected'
    ? { inputId: INPUT, status: 'BLOCKED', reason: result.reason, code: result.code }
    : result ? { inputId: INPUT, status: 'BLOCKED', reason: result.status === 'pending' ? '처리 결과를 확인한 뒤 다시 예약해 주세요.' : '이미 예약을 보냈습니다.' }
    : option ? { inputId: INPUT, ...option.availability }
    : { inputId: INPUT, status: 'BLOCKED', reason: '섬길 주공을 골라 주세요.' };
  const help = useReasonHelp(availability.code, INPUT);
  const denied = (error: Error | null) => error instanceof EnlistHttpError && (error.status === 401 || error.status === 403);
  async function reserve() {
    if (!option || option.availability.status !== 'AVAILABLE' || busy || result || submitError) return;
    const current = generation.current;
    setBusy(true);
    try {
      const next = await submitCommandAndAwaitResult(() => sendEnlist(generalId, option));
      if (current !== generation.current) return;
      setResult(next);
      if (next.status === 'reserved' || next.status === 'applied') onRefresh();
    } catch (error) {
      if (current !== generation.current) return;
      const failure = error instanceof Error ? error : new Error('출사 예약에 실패했습니다.');
      if (denied(failure)) setLoad({ kind: 'error', error: failure });
      else setSubmitError(failure);
    } finally { if (current === generation.current) setBusy(false); }
  }
  return <section className={styles.screen} aria-label="출사 — 주공 고르기" data-testid="enlist-screen">
    <aside className={styles.map} aria-label="주공 위치 지도" data-contract-id="K5-04">
      <StatusView kind="waiting" title="주공 위치는 서버 대기" body="주공의 초상 · 세력색 · 현 수 · 위치를 서버가 아직 주지 않습니다. 후보 이름은 아래에서 고를 수 있습니다." />
    </aside>
    <div className={`os-panel ${styles.panel}`}>
      <h2 className="os-serif">섬길 주공을 고른다</h2>
      <p>고른 주공에게 다음 개인 턴에 출사합니다. 그때 조건을 다시 확인합니다.</p>
      <InputHelpStrip inputId={INPUT} onOpenHelp={() => onHelp('input:action.enlist')} />
      {load.kind === 'loading' ? <StatusView kind="loading" /> : null}
      {load.kind === 'error' ? denied(load.error)
        ? <StatusView kind="denied" title={load.error.message} howTo={load.error instanceof EnlistHttpError && load.error.status === 401 ? '로그인한 뒤 다시 출사 화면을 열어 주세요.' : '본인의 장수로 다시 열어 주세요.'} />
        : <StatusView kind="error" title={load.error.message} onRetry={retry} /> : null}
      {data && data.options.length === 0 ? <StatusView kind="empty" title="지금 출사할 주공이 없습니다" body="첫 NPC 주공이 서기 전까지는 재야로 떠돌거나 직접 거병합니다. 주마다 NPC 주공이 설 때까지 기다릴 수도 있습니다." /> : null}
      {data && data.options.length > 0 ? <>
        <Seg label="출사 후보 묶음" options={GROUPS.map(g => ({ ...g, count: data.options.filter(o => o.mode === g.value).length }))}
          value={mode} onChange={next => { if (!busy) { setMode(next); setPicked(null); } }} scroll />
        <PeoplePicker key={mode} groups={ALL} load={{ state: 'ready', people }} label="섬길 주공" selected={picked}
          onChange={next => { if (!busy && !result) setPicked(next); }} />
        <div data-contract-id="K4-02"><p>몇 번째 순에: 1순</p><StatusView kind="waiting" title="순 고르기는 서버 대기" body="순 목록과 시각을 받기 전에는 1순에 예약합니다." /></div>
        {submitError ? denied(submitError)
          ? <StatusView kind="denied" title={submitError.message} howTo="로그인과 본인의 장수를 확인한 뒤 다시 열어 주세요." />
          : <StatusView kind="error" title={submitError.message} onRetry={retry} /> : null}
        {!submitError ? <div className={styles.actions}>
          <InputAction inputId={INPUT} availability={availability} label={busy ? '처리 중…' : '출사 예약'} busy={busy}
            onAct={() => void reserve()} {...help} onHelp={onHelp} />
        </div> : null}
      </> : null}
      {result ? <p role="status" data-command-outcome={result.status}>{result.status === 'reserved' ? '출사 명령이 예약되었습니다.'
        : result.status === 'applied' ? '출사 명령이 실행되었습니다.' : result.status === 'pending' ? '출사는 접수됐지만 처리 결과를 아직 확인하지 못했습니다.'
        : result.reason || '출사를 예약할 수 없습니다.'}</p> : null}
      {result?.status === 'rejected' ? <button type="button" className="os-button os-button--ghost" onClick={retry}>후보 다시 불러오기</button> : null}
      <div className={styles.actions}><CampaignLink slug="" className="os-button os-button--ghost">{result?.status === 'reserved' || result?.status === 'applied' ? '작전실로' : '재야로 시작'}</CampaignLink></div>
    </div>
  </section>;
}
