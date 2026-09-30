'use client';

import { ShellIcon } from './ShellIcon';
import styles from './shell.module.css';

/**
 * 도움말 서랍 자리(보드 Drawers — 데스크톱 400 · 태블릿 360 · 모바일 가득). 모달이 아니다 — 본문을 가리지 않고 옆에 선다.
 * `?help=<보기>` 가 있으면 열리고 닫기는 그 쿼리를 뺀 주소다. 내용(주제 · 입력 도움말 · 검색)은 K7 HelpPanel 이 이 자리에 들어온다.
 */
export default function HelpDrawer({ view, closeHref }: { readonly view: string; readonly closeHref: string }) {
  return (
    <aside className={styles.drawer} aria-label="도움말" data-help-view={view}>
      <div className={styles.drawerHead}>
        <span className={styles.sheetTitle}>도움말</span>
        <a className={styles.iconButton} href={closeHref} aria-label="도움말 닫기">
          <ShellIcon name="close" />
        </a>
      </div>
      <div className={styles.drawerBody}>
        <p className={styles.drawerWait}>도움말 내용은 아직 준비 중입니다. 준비되면 이 자리에 바로 보입니다.</p>
      </div>
    </aside>
  );
}
