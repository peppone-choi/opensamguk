'use client';

import { useEffect, useState, type ReactNode } from 'react';
import {
  InputAction,
  PeoplePicker,
  PickBar,
  StatusView,
  TargetCandidateList,
  TimeBar,
  useTargetPicker,
  type PersonOption,
  type TargetCandidate,
  type TimeBarEvent,
  type TimeBarSpeed,
} from '@opensamguk/ui';

// 합성 자료 — 보드 V31SystemMapPick · People · TimeBar 의 예시와 같은 이름 · 사유. 서버 값이 아니다.
const CANDIDATES: TargetCandidate[] = [
  { targetKind: 'place', targetId: 'yy', name: '영양현', sub: '영천군', cell: { col: 11, row: 9 }, available: true, distanceCells: 1, groups: ['내 영지'] },
  { targetKind: 'place', targetId: 'sj', name: '신정현', sub: '하남윤', cell: { col: 9, row: 6 }, available: false, reasonCode: 'NO_ROUTE', reason: '갈 길이 없음', distanceCells: 2, groups: ['이웃'] },
  { targetKind: 'place', targetId: 'mi', name: '밀현', sub: '하남윤', cell: { col: 8, row: 5 }, available: true, distanceCells: 3, groups: ['이웃'] },
  { targetKind: 'place', targetId: 'bc', name: '번창현', sub: '영천군 · 긴 설명 견본 — 목록 폭보다 길면 한 줄로 줄이고 끝에 말줄임표를 붙입니다', cell: { col: 13, row: 10 }, available: true, distanceCells: 3, groups: ['내 영지'] },
  { targetKind: 'place', targetId: 'mp', name: '마피영', sub: '영천군', cell: { col: 14, row: 12 }, available: false, reasonCode: 'INVALID_DESTINATION', reason: '갈 수 없는 곳', distanceCells: 4, groups: ['내 영지'] },
  { targetKind: 'place', targetId: 'yc', name: '양적현', sub: '영천군 · 지금 자리', cell: { col: 10, row: 9 }, available: false, reason: '지금 있는 곳입니다', distanceCells: 0, here: true, groups: ['내 영지'] },
];

const PEOPLE: PersonOption[] = [
  { generalId: 1, name: '순욱', isHuman: false, nation: { id: 1, name: '조조', color: '#4f7fbf' }, location: '허창', groups: ['mine', 'nation'] },
  { generalId: 2, name: '허저', isHuman: true, nation: { id: 1, name: '조조', color: '#4f7fbf' }, location: '양적현', groups: ['mine', 'nation'] },
  { generalId: 3, name: '곽가', isHuman: false, nation: { id: 1, name: '조조', color: '#4f7fbf' }, location: null, groups: ['nation'] },
  { generalId: 4, name: '원소', isHuman: false, nation: { id: 2, name: '원소', color: '#c96b5d' }, location: '업', groups: ['rulers'], blockedReason: '다른 세력 군주에게는 보낼 수 없습니다 — 긴 사유 견본: 좁은 화면에서는 꼬리표가 다음 줄로 내려갑니다' },
  { generalId: 5, name: '유표', isHuman: false, nation: { id: 3, name: '유표', color: '#7aa7c7' }, location: '양양', groups: ['rulers'] },
  { generalId: 6, name: '이전', isHuman: true, nation: null, location: '진류', groups: [] },
];

const EVENTS: TimeBarEvent[] = [
  { id: 'e1', at: 30_000, label: '부딪힘', tone: 'moss' },
  { id: 'e2', at: 77_000, label: '계책', tone: 'info' },
  { id: 'e3', at: 92_000, label: '일기토' },
  { id: 'e4', at: 145_000, label: '성문', tone: 'rust' },
  { id: 'e5', at: 185_000, label: '일기토' },
];

function Section({ id, title, children }: { readonly id: string; readonly title: string; readonly children: ReactNode }) {
  return (
    <section className="parts-lab__section" data-testid={`lab-${id}`} aria-labelledby={`lab-${id}-h`}>
      <h2 id={`lab-${id}-h`} className="parts-lab__h">{title}</h2>
      {children}
    </section>
  );
}

export default function PartsLab() {
  const [log, setLog] = useState('—');
  const [picking, setPicking] = useState(true);
  const picker = useTargetPicker({ kind: 'place', candidates: CANDIDATES, onCancel: () => { setPicking(false); setLog('고르기 그만'); } });
  const multi = useTargetPicker({ kind: 'multi-county', candidates: CANDIDATES.map((c) => ({ ...c, targetKind: 'multi-county' as const })), onCancel: () => undefined });
  const [one, setOne] = useState<number | null>(1);
  const [many, setMany] = useState<readonly number[]>([1, 2]);
  const [pos, setPos] = useState(92_000);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<TimeBarSpeed>(1);
  // 수화가 끝나 창 키 리스너(Esc)까지 붙은 뒤에만 시험이 누른다 — SSR 제목이 먼저 보여 경합했다(CI 1회 흔들림).
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);

  return (
    <main className="parts-lab" data-hydrated={hydrated ? 'true' : undefined}>
      <header className="parts-lab__head">
        <h1 className="parts-lab__title">공용 부품 미리보기</h1>
        <output className="parts-lab__log" data-testid="lab-log">{log}</output>
      </header>

      <Section id="input" title="입력 단추 — 4상태">
        <div className="parts-lab__row">
          <InputAction inputId="action.employ" availability={{ inputId: 'action.employ', status: 'AVAILABLE' }} label="등용 — 명령 목록에 넣기" onAct={() => setLog('등용 보냄')} />
          <InputAction
            inputId="court.dispatch"
            availability={{ inputId: 'court.dispatch', status: 'BLOCKED', code: 'NOT_RULER', reason: '주공만 · 내 부 사람 장수만' }}
            label="발령"
            onAct={() => setLog('발령이 불리면 안 된다')}
            reasonTitle="발령은 주공만 할 수 있습니다."
            recovery="주공이 되려면 거병하거나 독립해야 합니다."
            helpTopic={{ id: 'input:court.dispatch!NOT_RULER', title: '발령' }}
            onHelp={(id) => setLog(`도움말 ${id}`)}
          />
          <InputAction inputId="work.reduce" availability={{ inputId: 'work.reduce', status: 'NOT_DELIVERED' }} label="성방 허물기" onAct={() => setLog('준비 중이 불리면 안 된다')} />
          <InputAction inputId="action.unknown" availability={null} label="원장에 없는 입력" onAct={() => undefined} />
        </div>
        {/* 좁은 칸 — 요청 카드처럼 두 입력을 반씩 나눈 자리(모바일 ≈ 170). 보이는 사유가 잘리지 않아야 한다. */}
        <div className="parts-lab__narrow" data-testid="lab-narrow">
          <InputAction inputId="court.dispatchReply" availability={{ inputId: 'court.dispatchReply', status: 'BLOCKED', reason: '기한이 지났습니다' }} label="거절" onAct={() => undefined} variant="ghost" block />
          <InputAction inputId="court.dispatchReply" availability={{ inputId: 'court.dispatchReply', status: 'BLOCKED', reason: '기한이 지났습니다' }} label="수락" onAct={() => undefined} block />
        </div>
      </Section>

      <Section id="status" title="상태 — 빈 · 실패 · 권한 · 서버 대기 · 끊김 · 없음 · 점검">
        <div className="parts-lab__grid">
          <div className="parts-lab__box"><StatusView kind="loading" rows={3} delayMs={0} /></div>
          <div className="parts-lab__box"><StatusView kind="empty" title="지금 잡아 둔 포로가 없습니다" body="전투에서 이기면 포로를 잡을 수 있습니다." /></div>
          <div className="parts-lab__box"><StatusView kind="error" title="창고망을 불러오지 못했습니다" errorCode="E-7F3A" onRetry={() => setLog('다시 시도')} /></div>
          <div className="parts-lab__box"><StatusView kind="denied" title="발령은 주공만 할 수 있습니다" howTo="주공이 되려면 거병하거나 독립해야 합니다." helpTopic={{ id: 'topic:dispatch', title: '발령' }} onHelp={(id) => setLog(`도움말 ${id}`)} /></div>
          <div className="parts-lab__box"><StatusView kind="waiting" title="외교 관계를 아직 볼 수 없습니다" /></div>
          <div className="parts-lab__box"><StatusView kind="stale" lastReceived="3월 중순 21:40" onReconnect={() => setLog('다시 잇기')} /></div>
          <div className="parts-lab__box"><StatusView kind="not-found" /></div>
          <div className="parts-lab__box"><StatusView kind="maintenance" /></div>
        </div>
      </Section>

      <Section id="pick" title="지도 대상 고르기 — 띠 · 후보 목록">
        <div className="parts-lab__map" data-testid="lab-map">
          <div className="parts-lab__map-bg" aria-hidden="true" />
          {picking ? (
            <div className="parts-lab__pickbar">
              <PickBar title="갈 곳 고르기 — 이동 · 04순" hint="지도를 누르거나 목록에서" counts={picker.counts} onCancel={picker.cancel} />
            </div>
          ) : null}
          {CANDIDATES.filter((c) => c.cell).map((c) => (
            <button
              key={c.targetId}
              type="button"
              className={`parts-lab__marker parts-lab__marker--${picker.markerStateOf(c.targetId)}`}
              style={{ left: `${c.cell!.col * 6}%`, top: `${20 + c.cell!.row * 5}%` }}
              aria-label={`${c.name} 표지`}
              data-marker-state={picker.markerStateOf(c.targetId)}
              onClick={() => { if (!picker.pick(c.targetId)) setLog(`${c.name}: ${c.reason ?? ''}`); }}
            />
          ))}
        </div>
        <div className="parts-lab__panel">
          <TargetCandidateList picker={picker} candidates={CANDIDATES} groups={['내 영지', '이웃']} label="갈 곳 후보" />
        </div>
        <output data-testid="lab-picked">{picker.selected.join(',') || '—'}</output>
        <h3 className="parts-lab__h3">여러 현 고르기</h3>
        <div className="parts-lab__panel">
          <TargetCandidateList picker={multi} candidates={CANDIDATES} label="여러 현 후보" rowHeight={44} />
        </div>
        <output data-testid="lab-multi">{multi.selected.join(',') || '—'}</output>
      </Section>

      <Section id="people" title="사람 고르기 — 한 명 · 여러 명(NPC 포함)">
        <div className="parts-lab__grid parts-lab__grid--2">
          <div className="parts-lab__panel parts-lab__panel--tall">
            <PeoplePicker selected={one} onChange={setOne} load={{ state: 'ready', people: PEOPLE }} label="받는 사람" />
          </div>
          <div className="parts-lab__panel parts-lab__panel--tall">
            <PeoplePicker multiple selected={many} onChange={setMany} load={{ state: 'ready', people: PEOPLE }} label="알릴 사람" />
          </div>
        </div>
        <output data-testid="lab-person">{one ?? '—'}</output>
      </Section>

      <Section id="timebar" title="시간 막대 — 다시 보기 · 실시간">
        <TimeBar mode="replay" duration={250_000} position={pos} events={EVENTS} nowText="우리 선봉 하후돈이 적 좌익과 일기토"
          onSeek={setPos} playing={playing} onPlayPause={() => setPlaying((p) => !p)} speed={speed} onSpeed={setSpeed} onResult={() => setLog('결과')} />
        <div className="parts-lab__gap" />
        <TimeBar mode="live" elapsed={160_000} position={pos > 160_000 ? 160_000 : pos} events={EVENTS} nowText="적 본대가 성문에 붙었다"
          onSeek={setPos} onJumpLive={() => setPos(160_000)} />
        <output data-testid="lab-pos">{pos}</output>
      </Section>

      <style>{`
        .parts-lab { max-width: 1200px; margin: 0 auto; padding: 16px; display: flex; flex-direction: column; gap: 24px; color: var(--text); }
        .parts-lab__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
        .parts-lab__title { font-family: var(--font-serif); font-size: 20px; margin: 0; }
        .parts-lab__log { font-size: 12px; color: var(--text-2); }
        .parts-lab__section { display: flex; flex-direction: column; gap: 10px; }
        .parts-lab__h { font-size: 15px; margin: 0; color: var(--bronze); }
        .parts-lab__h3 { font-size: 13px; margin: 8px 0 0; color: var(--text-2); }
        .parts-lab__row { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; }
        .parts-lab__grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
        .parts-lab__grid--2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
        .parts-lab__box { min-height: 220px; display: flex; background: var(--panel); border: 1px solid var(--line-2); }
        .parts-lab__panel { width: 336px; max-width: 100%; background: var(--panel); border: 1px solid var(--line-2); }
        .parts-lab__grid .parts-lab__panel { width: auto; }
        .parts-lab__panel--tall { height: 520px; display: flex; flex-direction: column; }
        .parts-lab__map { position: relative; height: 360px; overflow: hidden; border: 1px solid var(--line-2); }
        .parts-lab__map-bg { position: absolute; inset: 0; background: repeating-linear-gradient(45deg, #26302a 0 12px, #222a25 12px 24px); pointer-events: none; }
        .parts-lab__pickbar { position: absolute; left: 12px; right: 12px; top: 12px; z-index: 3; }
        .parts-lab__marker { position: absolute; z-index: 2; width: 44px; height: 44px; margin: -22px 0 0 -22px; padding: 0; border: 0; background: transparent; cursor: pointer; }
        .parts-lab__marker::after { content: ''; position: absolute; inset: 14px; border: 2px solid var(--moss-2); background: rgba(143, 167, 122, 0.3); pointer-events: none; }
        .parts-lab__marker--no::after { border: 2px dashed var(--rust-2); background: transparent; }
        .parts-lab__marker--selected::after { border-color: var(--focus); background: var(--focus); }
        .parts-lab__marker--here::after { border-color: var(--bronze); background: rgba(211, 176, 100, 0.4); }
        .parts-lab__gap { height: 8px; }
        .parts-lab__narrow { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 340px; max-width: 100%; margin-top: 8px; }
        @media (max-width: 767.98px) {
          .parts-lab { padding: 12px 16px; }
          .parts-lab__grid, .parts-lab__grid--2 { grid-template-columns: minmax(0, 1fr); }
          .parts-lab__panel { width: 100%; }
          .parts-lab__map { height: 300px; }
          .parts-lab__pickbar { left: 8px; right: 8px; top: 8px; }
        }
      `}</style>
    </main>
  );
}
