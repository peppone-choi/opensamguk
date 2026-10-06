import { notFound } from 'next/navigation';
import ReplayLab from './ReplayLab';

export const dynamic = 'force-dynamic';

/**
 * 다시 보기(P-H03) 부품 시험 화면 — 전장 판(읽기 전용) + 시간 막대 재생 시계. 메뉴에 없고, 부품 시험과 같은 기능 플래그
 * (NEXT_PUBLIC_PARTS_LAB=1)나 로컬 개발에서만 열린다. 합성 사건만 쓴다(서버 값 아님) — 리플레이 본문 모양이 합의되면
 * 제품 화면(records/replay/[id])이 같은 부품을 서버 값으로 쓴다.
 */
export default async function ReplayLabPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
    const enabled = process.env.NEXT_PUBLIC_PARTS_LAB === '1' || process.env.NODE_ENV === 'development';
    if (!enabled) notFound();
    const params = await searchParams;
    const board = Number(params.board ?? 4);
    return <ReplayLab boardId={Number.isInteger(board) && board >= 0 ? board : 4} />;
}
