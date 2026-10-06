'use client';

import { StatusView } from '@opensamguk/ui';
import WarRoomPage from '@/components/campaign/WarRoomPage';
import EntryScreen from '@/components/entry/EntryScreen';
import { useGameSession } from '@/lib/campaign-session';

export default function GameEntry() {
  const session = useGameSession();
  if (session.loading) return <p role="status">장수 정보를 불러오는 중입니다.</p>;
  if (session.error || !session.frontInfo) return <StatusView kind="error" title="장수 정보를 불러오지 못했습니다." onRetry={session.refresh} />;
  if (session.generalId == null) return <EntryScreen />;
  return <WarRoomPage />;
}
