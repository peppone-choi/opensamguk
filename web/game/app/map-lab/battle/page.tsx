import { notFound } from 'next/navigation';
import BattleLab from './BattleLab';

export const dynamic = 'force-dynamic';

/**
 * 전투 판 유닛(분대 표기 B안) 시험 화면. 지도 시험과 같은 기능 플래그(NEXT_PUBLIC_MAP_RENDERER=topdown)나
 * 로컬 개발에서만 열린다. 실시간 전투 화면(K6)에 붙이면 지운다.
 */
export default async function BattleLabPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const enabled = process.env.NEXT_PUBLIC_MAP_RENDERER === 'topdown' || process.env.NODE_ENV === 'development';
  if (!enabled) notFound();
  const params = await searchParams;
  return <BattleLab kitUrl={params.kit ?? '/battle/waryong/2c8a1a5'} boardId={Number(params.board ?? 4)} />;
}
