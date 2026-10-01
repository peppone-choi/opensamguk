'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { StatusView } from '@opensamguk/ui';
import EnlistScreen from '@/components/enlist/EnlistScreen';
import { useOpenHelp } from '@/hooks/useHelp';
import { useGameSession } from '@/lib/campaign-session';
import { resolveServerGamePath } from '@/lib/serverGameUrl';

export default function EnlistPage() {
  const session = useGameSession();
  const router = useRouter();
  const onHelp = useOpenHelp();
  const entryHref = session.serverId ? resolveServerGamePath(undefined, session.serverId) : '/game';
  const mustReturn = !session.loading && !session.error && !!session.frontInfo
    && (session.generalId == null || session.frontInfo.general.nationId > 0);
  useEffect(() => { if (mustReturn) router.replace(entryHref); }, [entryHref, mustReturn, router]);
  if (session.loading) return <p role="status">장수 정보를 불러오는 중입니다.</p>;
  if (session.error || !session.frontInfo) return <StatusView kind="error" title="장수 정보를 불러오지 못했습니다." onRetry={session.refresh} />;
  if (mustReturn || session.generalId == null) return <p role="status">{session.generalId == null ? '게임 입구로 이동합니다.' : '이미 소속된 장수입니다. 작전실로 이동합니다.'}</p>;
  return <EnlistScreen generalId={session.generalId} onRefresh={session.refresh} onHelp={onHelp} />;
}
