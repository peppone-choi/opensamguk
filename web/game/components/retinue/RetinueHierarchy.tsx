'use client';

import type { CSSProperties } from 'react';
import { StatusView } from '@opensamguk/ui';
import { useGameSession } from '@/lib/campaign-session';
import { hierarchyRows } from '@/lib/retinue-hierarchy-view';
import { useRetinueHierarchy } from '@/lib/use-retinue-hierarchy';
import styles from './hierarchy.module.css';

/** Structural names and counts only: no private person details or inferred command actions. */
export function RetinueHierarchy() {
  const { generalId, serverId, frontInfo } = useGameSession();
  const turn = frontInfo ? `${frontInfo.global.year}:${frontInfo.global.month}:${frontInfo.global.turnPhase ?? ''}` : '';
  const { state, reload } = useRetinueHierarchy(generalId, serverId, turn);
  return <section className={`os-panel ${styles.panel}`} aria-label="부 조직도">
    <header className={styles.heading}><h2 className="os-serif">조직도</h2><p>직속 관계와 하위 부</p></header>
    {state.kind === 'loading' ? <StatusView kind="loading" rows={2} />
      : state.kind === 'error' ? state.denied
        ? <StatusView kind="denied" title={state.message} howTo="로그인과 본인의 장수를 확인한 뒤 다시 열어 주세요." />
        : <StatusView kind="error" title={state.message} onRetry={reload} />
        : state.kind === 'unavailable' ? <StatusView kind="unavailable" title="조직도를 확인할 수 없습니다." body="관계를 확인하지 못했습니다. 잠시 뒤 다시 읽어 주세요." onReload={reload} />
          : <>
            <div className={styles.superiors}>
              <h3>내 상관</h3>
              {state.data.superiors.length === 0 ? <p className={styles.note}>직속 상관이 없습니다.</p>
                : <ol aria-label="상관 계보">{state.data.superiors.map((s, i) => <li key={s.generalId}>
                  <span className={styles.relation}>{i === 0 ? '직속 상관' : `${i + 1}단계 상관`}</span><strong className="os-serif">{s.name}</strong>
                </li>)}</ol>}
            </div>
            <ol className={styles.tree} aria-label="내 부 계층">
              {hierarchyRows(state.data).map(({ node, depth, parentName }) => <li key={node.generalId}
                data-depth={depth} data-general-id={node.generalId}
                style={{ '--hierarchy-indent': Math.min(depth, 4) } as CSSProperties}>
                <div className={styles.node} data-self={depth === 0 || undefined}>
                  <div className={styles.identity}><span className={styles.relation}>{depth === 0 ? '본인' : `${depth}단계 하위 장수`}</span>
                    <strong className="os-serif">{node.name}</strong>
                    <span className={styles.parent}>{parentName ? `${parentName}의 직속` : '상관 없음'}</span></div>
                  <dl className={styles.counts}><div><dt>직속 장수</dt><dd className="os-mono">{node.directCount}명</dd></div>
                    <div><dt>전체 하위 장수</dt><dd className="os-mono">{node.descendantCount}명</dd></div></dl>
                </div>
              </li>)}
            </ol>
            {state.data.nodes[0].directCount === 0 ? <p className={styles.empty}>아직 직속 장수가 없습니다.</p> : null}
          </>}
  </section>;
}
