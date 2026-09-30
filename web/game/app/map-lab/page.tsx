import { notFound } from 'next/navigation';
import MapLab from './MapLab';

export const dynamic = 'force-dynamic';

/**
 * 탑다운 지도(K2 지도 M2) 시험 화면. 메뉴에 없고, 기능 플래그(NEXT_PUBLIC_MAP_RENDERER=topdown)나
 * 로컬 개발에서만 열린다. 화면 설계 승인 뒤 제품 화면에 붙이면 지운다.
 */
export default async function MapLabPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const enabled = process.env.NEXT_PUBLIC_MAP_RENDERER === 'topdown' || process.env.NODE_ENV === 'development';
  if (!enabled) notFound();
  const params = await searchParams;
  return (
    <MapLab
      bakeUrl={params.bake ?? '/map/topdown-lab/bake'}
      kitUrl={params.kit ?? '/map/topdown-lab/kit'}
      view={params.view ?? 'luoyang'}
    />
  );
}
