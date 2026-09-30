import { notFound } from 'next/navigation';
import PartsLab from './PartsLab';

export const dynamic = 'force-dynamic';

/**
 * v3.1 공용 부품 미리보기(K3). 메뉴에 없고, 기능 플래그(NEXT_PUBLIC_PARTS_LAB=1)나 로컬 개발에서만 열린다.
 * 합성 자료만 쓴다(서버 없음) — smoke e2e(e2e/smoke/parts-lab.spec.ts)가 데스크톱 · 모바일로 부품 규칙을 확인한다.
 */
export default function PartsLabPage() {
  const enabled = process.env.NEXT_PUBLIC_PARTS_LAB === '1' || process.env.NODE_ENV === 'development';
  if (!enabled) notFound();
  return <PartsLab />;
}
