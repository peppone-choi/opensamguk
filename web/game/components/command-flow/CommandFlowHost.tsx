'use client';

// 명령 흐름을 담는 자리 — 설계서 §2.1 배치. 데스크톱(≥1200): 작전실 12순 열 자리(576, 부른 쪽 격자 칸),
// 태블릿(768–1199): 지도 위 오른쪽 480 겹침, 모바일(<768): 머리줄 아래 전체 시트(아래 탭을 가린다 — 닫으면 돌아온다).
// 모달이 아니다 — 지도를 덮는 배경을 두지 않는다(태블릿 · 모바일은 자리 자체가 겹친다).
import type { ReactNode } from 'react';
import styles from './CommandFlowHost.module.css';

export function CommandFlowHost({ children }: { readonly children: ReactNode }) {
    return <div className={styles.host} data-testid="command-flow-host">{children}</div>;
}

export default CommandFlowHost;
