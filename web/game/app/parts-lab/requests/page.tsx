import { notFound } from 'next/navigation';
import RequestLab from './RequestLab';

export const dynamic = 'force-dynamic';

/**
 * 요청 카드 미리보기(K6). 메뉴에 없고, 공용 부품 미리보기와 같은 기능 플래그(NEXT_PUBLIC_PARTS_LAB=1)나 로컬 개발에서만 열린다.
 * 받은 요청 읽기 · 응답 보내기는 실제 경로를 타고, 서버 응답은 smoke e2e(e2e/smoke/request-card.spec.ts)가 합성 자료로 대 준다.
 */
export default function RequestLabPage() {
    const enabled = process.env.NEXT_PUBLIC_PARTS_LAB === '1' || process.env.NODE_ENV === 'development';
    if (!enabled) notFound();
    return <RequestLab />;
}
