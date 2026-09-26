'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import WarRoomPage from '@/app/game/(campaign)/war-room/page';
import { useGameSession } from '@/lib/campaign-session';
import { resolveServerGamePath } from '@/lib/serverGameUrl';

export default function GameEntry() {
  const session = useGameSession();
  const router = useRouter();
  // 장수가 없으면 가입(장수 생성·역사 인물 선택)으로 보낸다. 삼모 빙의·선택 풀 입구는 대체 없이 없앴다(ADR-LITE-049 2026-09-26).
  const joinHref = session.serverId
    ? resolveServerGamePath(undefined, session.serverId, '/game', 'join')
    : '/game/join';

  useEffect(() => {
    if (!session.loading && !session.error && session.frontInfo && session.generalId == null) {
      router.replace(joinHref);
    }
  }, [joinHref, router, session.error, session.frontInfo, session.generalId, session.loading]);

  if (session.loading) return <p role="status">장수 정보를 불러오는 중입니다.</p>;
  if (session.error || !session.frontInfo) {
    return (
      <div role="alert">
        <p>{session.error ?? '장수 정보를 불러오지 못했습니다.'}</p>
        <button type="button" onClick={session.refresh}>다시 시도</button>
      </div>
    );
  }
  if (session.generalId == null) return <p role="status">장수 등록 화면으로 이동합니다.</p>;

  return <WarRoomPage />;
}
