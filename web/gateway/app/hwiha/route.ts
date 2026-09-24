import { NextRequest } from 'next/server';
import { redirectLegacyHwiha } from '@/lib/hwihaLegacyRoute';

export function GET(req: NextRequest) {
  return redirectLegacyHwiha(req, []);
}
