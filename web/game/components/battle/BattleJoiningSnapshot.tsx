'use client';

import { useState } from 'react';
import { joiningUnitId, type JoiningSnapshot } from '@/lib/battle/joining-snapshot';
import { BattleBoardCanvas } from './BattleBoardCanvas';
import styles from './BattleJoiningSnapshot.module.css';

/** Read-only projection of the actual JOINING publisher, without draft live facts. */
export function BattleJoiningSnapshot({ snapshot }: { readonly snapshot: JoiningSnapshot }) {
  const [selected, setSelected] = useState<string | null>(null);
  const unit = snapshot.ownUnits.find(item => joiningUnitId(item.sourceKey) === selected);
  const selectedIds = new Set(unit ? [joiningUnitId(unit.sourceKey)] : []);
  return (
    <section className={styles.root} data-testid="battle-joining-snapshot" aria-label="전투 참가 대기">
      <header>
        <h2>전투 참가 대기</h2>
        <p>서버가 정한 내 부곡 배치를 확인합니다. 현재 이 화면에서는 배치 변경과 전투 명령을 보낼 수 없습니다.</p>
        <p>참가 마감: <time dateTime={snapshot.joinDeadlineAt}>{snapshot.joinDeadlineAt}</time></p>
      </header>
      <div className={styles.body}>
        <div className={styles.board}>
          <BattleBoardCanvas boardId={snapshot.field.boardId} terrainInputSha256={snapshot.field.terrainInputSha256}
            units={snapshot.ownUnits.map((item, index) => ({ id: joiningUnitId(item.sourceKey), cell: item.cell, index: index + 1 }))}
            allowedCells={snapshot.deployment.allowedCells} selectedIds={selectedIds} scale={{ kind: 'fit' }}
            onPickUnit={setSelected} onPickCell={() => {}} label="서버가 정한 내 부곡 배치 판" />
          <p>판의 숫자는 아래 목록 순서입니다. 부곡을 고르면 위치와 병력을 확인할 수 있습니다.</p>
        </div>
        <div className={styles.list}>
          <h3>내 부곡 {snapshot.ownUnits.length}개</h3>
          {snapshot.ownUnits.length === 0 ? <p>이 전투에서 내 지휘권에 속하는 부곡이 없습니다.</p> : (
            <ul>{snapshot.ownUnits.map((item, index) => (
              <li key={joiningUnitId(item.sourceKey)}>
                <button type="button" aria-pressed={selected === joiningUnitId(item.sourceKey)} onClick={() => setSelected(joiningUnitId(item.sourceKey))}>
                  <strong>{index + 1}. 부곡 {item.sourceKey.sourceId}</strong>
                  <span>병력 {item.troops.toLocaleString('ko-KR')} · 행 {item.cell.row}, 열 {item.cell.col}</span>
                </button>
              </li>
            ))}</ul>
          )}
          {unit ? <p role="status">고른 부곡 {unit.sourceKey.sourceId}: 행 {unit.cell.row}, 열 {unit.cell.col} · 병력 {unit.troops.toLocaleString('ko-KR')}</p> : null}
        </div>
      </div>
    </section>
  );
}
