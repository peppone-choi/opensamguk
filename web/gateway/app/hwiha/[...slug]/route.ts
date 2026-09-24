import { NextRequest } from 'next/server';
import { redirectLegacyHwiha } from '@/lib/hwihaLegacyRoute';

export async function GET(req: NextRequest, context: { params: Promise<{ slug: string[] }> }) {
  const { slug } = await context.params;
  return redirectLegacyHwiha(req, slug);
}
