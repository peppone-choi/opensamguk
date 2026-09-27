import { NextRequest } from 'next/server';
import { redirectLegacyCampaign } from '@/lib/legacyCampaignRoute';

export function GET(req: NextRequest) {
  return redirectLegacyCampaign(req, []);
}
