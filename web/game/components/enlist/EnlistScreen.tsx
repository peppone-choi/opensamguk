'use client';

import { useMemo } from 'react';
import { InputAction, PeoplePicker, Seg, StatusView, type PersonOption } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { InputHelpStrip } from '@/components/help/HelpStrip';
import { TurnSlots } from '@/components/turn-slots/TurnSlots';
import { useReasonHelp } from '@/hooks/useHelp';
import { isEnlistDenied, useEnlist } from '@/hooks/useEnlist';
import { candidateKey, ENLIST_INPUT, receiptText, slotNo } from '@/lib/enlist/enlist-view';
import styles from './EnlistScreen.module.css';

const GROUPS = [{ value: 'NATION', label: '세력' }, { value: 'GENERAL', label: '장수' }, { value: 'RANDOM', label: '무작위' }] as const;
const ALL = ['all'] as const;

/** E04 approved board; connected through the guarded K5 join route. */
export default function EnlistScreen({ generalId, onRefresh, onHelp }: {
  readonly generalId: number;
  readonly onRefresh: () => void;
  readonly onHelp: (topicId: string) => void;
}) {
  const enlist = useEnlist(generalId, onRefresh);
  const { availability, receipt, options, candidates, denied, loadError, submitFailure, busy } = enlist;
  // Local array keys identify picker rows only. The hook keeps the choice by mode + original targetId.
  const people = useMemo<readonly PersonOption[]>(() => candidates.map((row, index) => ({
    generalId: index, name: row.label, groups: [],
    ...(row.availability.status === 'BLOCKED' ? { blockedReason: row.availability.reason?.trim() || '사유를 받지 못했습니다' } : {}),
  })), [candidates]);
  const pickedIndex = candidates.findIndex(row => candidateKey(row) === enlist.candidateKey);
  const help = useReasonHelp(availability.code, ENLIST_INPUT);
  const done = receipt?.status === 'reserved' || receipt?.status === 'applied';
  return <section className={styles.screen} aria-label="출사 — 주공 고르기" data-testid="enlist-screen">
    <aside className={styles.map} aria-label="주공 위치 지도" data-contract-id="K5-04">
      <StatusView kind="waiting" title="주공 위치는 서버 대기" body="주공의 초상 · 세력색 · 현 수 · 위치를 서버가 아직 주지 않습니다. 후보 이름은 아래에서 고를 수 있습니다." />
    </aside>
    <div className={`os-panel ${styles.panel}`}>
      <h2 className="os-serif">섬길 주공을 고른다</h2>
      <p>고른 주공에게 고른 순의 개인 턴에 출사합니다. 그때 조건을 다시 확인합니다.</p>
      <InputHelpStrip inputId={ENLIST_INPUT} onOpenHelp={() => onHelp('input:action.enlist')} />
      {denied ? <StatusView kind="denied" title={denied.message}
        howTo={denied.status === 401 ? '로그인한 뒤 다시 출사 화면을 열어 주세요.' : '본인의 장수로 다시 열어 주세요.'} /> : <>
        {enlist.loading ? <StatusView kind="loading" /> : null}
        {loadError ? <StatusView kind="error" title={loadError.message} onRetry={enlist.retry} /> : null}
        {options && options.length === 0 ? <StatusView kind="empty" title="지금 출사할 주공이 없습니다" body="첫 NPC 주공이 서기 전까지는 재야로 떠돌거나 직접 거병합니다. 주마다 NPC 주공이 설 때까지 기다릴 수도 있습니다." /> : null}
        {options && options.length > 0 ? <>
          <Seg label="출사 후보 묶음" options={GROUPS.map(g => ({ ...g, count: options.filter(o => o.mode === g.value).length }))}
            value={enlist.mode} onChange={next => { if (!busy) enlist.chooseMode(next); }} scroll />
          <PeoplePicker key={enlist.mode} groups={ALL} load={{ state: 'ready', people }} label="섬길 주공"
            selected={pickedIndex < 0 ? null : pickedIndex}
            onChange={next => { const row = next == null ? undefined : candidates[next]; if (row) enlist.chooseCandidate(candidateKey(row)); }} />
          <div className={styles.slots} data-contract-id="K4-02">
            <p>몇 번째 순에: {enlist.turnIdx == null ? '빈 순 없음' : slotNo(enlist.turnIdx)}</p>
            <TurnSlots mode="column" load={enlist.slotsLoad} current={enlist.turnIdx} busy={busy || !!receipt}
              onSelect={turnIdx => enlist.chooseSlot(turnIdx)} onRetry={enlist.retry} />
          </div>
          {submitFailure && !isEnlistDenied(submitFailure) ? <StatusView kind="error" title={submitFailure.message} onRetry={enlist.retry} /> : null}
          {!submitFailure ? <div className={styles.actions}>
            <InputAction inputId={ENLIST_INPUT} availability={availability} label={busy ? '처리 중…' : '출사 예약'} busy={busy}
              onAct={() => void enlist.reserve()} {...help} onHelp={onHelp} />
          </div> : null}
        </> : null}
      </>}
      {receipt ? <p role="status" data-command-outcome={receipt.status}>{receiptText(receipt)}</p> : null}
      {receipt?.status === 'rejected' ? <button type="button" className="os-button os-button--ghost" onClick={enlist.acknowledge}>후보 다시 불러오기</button> : null}
      <div className={styles.actions}><CampaignLink slug="" className="os-button os-button--ghost">{done ? '작전실로' : '재야로 시작'}</CampaignLink></div>
    </div>
  </section>;
}
