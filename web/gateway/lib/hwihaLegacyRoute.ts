import { NextRequest, NextResponse } from 'next/server';

export function redirectLegacyHwiha(req: NextRequest, slug: readonly string[]): NextResponse {
  const target = req.nextUrl.clone();
  target.pathname = `/game/hwiha/${slug.length ? slug.map(encodeURIComponent).join('/') : 'war-room'}`;
  return new NextResponse(null, {
    status: 308,
    headers: { Location: `${target.pathname}${target.search}` },
  });
}
