'use client';

// `/game/{id}` 공개 전 화면(D112 ② A안 · D120). 껍데기(HTML)는 그대로 내고, 첫 front-info 가 game-api admission 에 막히면
// (403 SERVER_NOT_PUBLIC · 503 SERVER_ADMISSION_UNAVAILABLE) 게임 화면 대신 이 화면만 보인다.
// 자료는 game-api admission 이 막는다 — 이 화면은 그 결과를 쉬운 말로 보일 뿐이고, HTML 진입 차단 완료로 세지 않는다.
import type { ReactNode } from 'react';
import { Icon, StatusView } from '@opensamguk/ui';
import { useGameSession } from '@/lib/campaign-session';
import { LOBBY_HREF } from '@/lib/gatewayLinks';
import styles from './admission.module.css';

export default function AdmissionGate({ children }: { readonly children: ReactNode }) {
  const { admission, refresh } = useGameSession();
  if (!admission) return <>{children}</>;
  return (
    <main className={styles.page} aria-label="게임 콘텐츠" data-admission={admission}>
      {admission === 'not-public' ? (
        <StatusView
          kind="empty"
          scope="page"
          title="이 서버는 지금 공개되지 않았습니다"
          body="확인이 끝나면 다시 열립니다. 다른 서버는 로비에서 고를 수 있습니다."
          actions={<a className="os-button os-status__action" href={LOBBY_HREF}><Icon name="lobby" size={16} />로비로</a>}
        />
      ) : (
        <StatusView kind="unavailable" scope="page" title="서버 공개 상태를 확인하지 못했습니다" body="잠시 뒤 다시 시도해 주세요." onReload={refresh} />
      )}
    </main>
  );
}
