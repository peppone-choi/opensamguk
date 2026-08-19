import React from 'react';
import { onPortraitError, portraitUrl } from '@/lib/portrait';

// 전콘 URL 은 서버가 아니라 여기서 만든다 — portraitUrl 이 managed 파일명을 화이트리스트해
// 임의 URL 주입을 막고 있고, CDN/`/d_pic` 규약이 한 군데에만 있어야 갈라지지 않는다.
export default function BoardAuthor({
  name,
  picture,
  imageServer,
  size = 32,
}: {
  readonly name: string;
  readonly picture?: string | null;
  readonly imageServer?: number | null;
  readonly size?: number;
}) {
  return (
    <span className="board-author">
      {/* alt 를 비운다 — 바로 옆에 같은 이름이 텍스트로 있어서 스크린리더가 두 번 읽는다. */}
      <img
        alt=""
        className="board-author-icon"
        height={size}
        loading="lazy"
        onError={onPortraitError}
        src={portraitUrl(picture, imageServer)}
        width={size}
      />
      <span>{name}</span>
    </span>
  );
}
