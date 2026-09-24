'use client';

import { useEffect } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import WarRoomPage from '@/app/game/hwiha/war-room/page';
import CharacterClaim from '@/components/game/CharacterClaim';
import { hwihaHref } from '@/lib/hwiha-screens';
import { useHwihaSession } from '@/lib/hwiha-session';
import { resolveServerGamePath } from '@/lib/serverGameUrl';

export default function GameEntry() {
  const session = useHwihaSession();
  const router = useRouter();
  const possessionEntry = useSearchParams().get('entry') === 'possession';
  const joinHref = session.serverId
    ? resolveServerGamePath(undefined, session.serverId, '/game', 'join')
    : '/game/join';
  const selectionEntryHref = session.serverId
    ? `${resolveServerGamePath(undefined, session.serverId, '/game')}?entry=possession`
    : '/game?entry=possession';
  const global = session.frontInfo?.global;
  const selectionOnly = global != null
    && ((global.blockGeneralCreate ?? 0) & 1) !== 0
    && (global.npcMode === 1 || global.npcMode === 2);

  useEffect(() => {
    if (!session.loading && !session.error && session.frontInfo && session.generalId == null && !possessionEntry) {
      router.replace(selectionOnly ? selectionEntryHref : joinHref);
    }
  }, [joinHref, possessionEntry, router, selectionEntryHref, selectionOnly, session.error, session.frontInfo, session.generalId, session.loading]);

  if (session.loading) return <p role="status">장수 정보를 불러오는 중입니다.</p>;
  if (session.error || !session.frontInfo) {
    return (
      <div role="alert">
        <p>{session.error ?? '장수 정보를 불러오지 못했습니다.'}</p>
        <button type="button" onClick={session.refresh}>다시 시도</button>
      </div>
    );
  }
  if (session.generalId == null) {
    if (possessionEntry) {
      return <CharacterClaim global={session.frontInfo.global} onClaimed={() => {
        session.refresh();
        router.replace(hwihaHref('war-room', session.serverId));
      }} />;
    }
    return <p role="status">장수 등록 화면으로 이동합니다.</p>;
  }

  return <WarRoomPage />;
}
