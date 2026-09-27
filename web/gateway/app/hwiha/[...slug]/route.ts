import { NextRequest } from 'next/server';
import { redirectLegacyCampaign } from '@/lib/legacyCampaignRoute';

export async function GET(req: NextRequest, context: { params: Promise<{ slug: string[] }> }) {
  const { slug } = await context.params;
  return redirectLegacyCampaign(req, slug);
}
