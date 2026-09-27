import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { GET as root } from '@/app/hwiha/route';
import { GET as screen } from '@/app/hwiha/[...slug]/route';

describe('old HWIHA addresses', () => {
  it('redirects the hub to the game route', () => {
    const response = root(new NextRequest('https://gateway.example.test/hwiha?from=bookmark'));
    expect(response.status).toBe(308);
    expect(response.headers.get('location')).toBe('/game/war-room?from=bookmark');
  });

  it('preserves a screen path and query for the game server redirect', async () => {
    const response = await screen(
      new NextRequest('https://gateway.example.test/hwiha/retinue?person=17'),
      { params: Promise.resolve({ slug: ['retinue'] }) },
    );
    expect(response.status).toBe(308);
    expect(response.headers.get('location')).toBe('/game/retinue?person=17');
  });
});
