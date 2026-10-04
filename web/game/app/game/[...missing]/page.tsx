import { notFound } from 'next/navigation';

/**
 * /game 아래에서 맞는 화면이 없는 주소 — 뿌리 404(셸 밖) 대신 /game 의 not-found.tsx(셸 안)로 보낸다.
 * 이미 있는 화면 · 옛 주소 308(middleware)이 먼저 잡으므로 여기 오는 것은 정말 없는 주소뿐이다.
 */
export default function MissingGameScreen(): never {
    notFound();
}
