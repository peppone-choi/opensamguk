import { NextRequest, NextResponse } from 'next/server';

export function redirectLegacyCampaign(req: NextRequest, slug: readonly string[]): NextResponse {
  const target = req.nextUrl.clone();
  target.pathname = `/game/${slug.length ? slug.map(encodeURIComponent).join('/') : 'war-room'}`;
  return new NextResponse(null, {
    status: 308,
    headers: { Location: `${target.pathname}${target.search}` },
  });
}
