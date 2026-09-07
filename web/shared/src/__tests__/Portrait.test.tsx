import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PORTRAIT_SIZES, Portrait, PortraitResolverProvider, PortraitStack, portraitVariantForSize, type PortraitSize } from '../Portrait';
import { createPortraitResolver, DEFAULT_IMAGE_CDN_BASE, DEFAULT_PORTRAIT_PATH } from '../portraitResolver';

const SERVING = `${DEFAULT_IMAGE_CDN_BASE}/portraits/rtk14/serving`;

describe('Portrait 3종 규칙', () => {
  it.each<[PortraitSize, string]>([
    ['hero', `${SERVING}/original/10001.jpg`],
    ['card', `${SERVING}/portrait/10001.png`],
    ['card-126', `${SERVING}/portrait/10001.png`],
    ['card-36', `${SERVING}/portrait/10001.png`],
    ['icon', `${SERVING}/icon/10001.png`],
    ['icon-48', `${SERVING}/icon/10001.png`],
    ['icon-20', `${SERVING}/icon/10001.png`],
  ])('size %s picks the matching RTK14 variant', (size, url) => {
    render(<Portrait picture="10001" imageServer={0} size={size} alt="하후돈" />);
    expect(screen.getByRole('img', { name: '하후돈' })).toHaveAttribute('src', url);
  });

  it('exposes width/height presets for cards and icons and fills the frame for hero', () => {
    const { container: card } = render(<Portrait picture="10001" size="card" alt="a" />);
    expect(card.querySelector('.os-portrait')).toHaveStyle({ width: '148px', height: '210px' });
    expect(card.querySelector('img')).toHaveAttribute('width', '148');
    const { container: hero } = render(<Portrait picture="10001" size="hero" alt="b" />);
    expect(hero.querySelector('.os-portrait')).toHaveClass('os-portrait--hero');
    expect(hero.querySelector('.os-portrait')).toHaveStyle({ width: '100%', height: '100%' });
    expect(portraitVariantForSize('card-56')).toBe('portrait');
    expect(PORTRAIT_SIZES['icon-32'].w).toBe(32);
  });

  it('draws the nation ring only when a reason is given', () => {
    const { container } = render(<Portrait picture="10001" size="icon-32" alt="a" ring={{ color: '#3f6fb5', reason: 'self' }} />);
    const frame = container.querySelector('.os-portrait') as HTMLElement;
    expect(frame).toHaveClass('os-portrait--ring');
    expect(frame.getAttribute('data-ring')).toBe('self');
    expect(frame.style.getPropertyValue('--nation')).toBe('#3f6fb5');
    const { container: bare } = render(<Portrait picture="10001" size="icon-32" alt="b" ring={null} />);
    expect(bare.querySelector('.os-portrait')).not.toHaveClass('os-portrait--ring');
  });

  it('marks inactive portraits and falls back to the default image on error', () => {
    const { container } = render(<Portrait picture="10001" size="icon-24" alt="a" inactive />);
    expect(container.querySelector('.os-portrait')).toHaveClass('os-portrait--inactive');
    const img = screen.getByRole('img', { name: 'a' }) as HTMLImageElement;
    fireEvent.error(img);
    expect(img.src).toBe(new URL(DEFAULT_PORTRAIT_PATH, document.baseURI).href);
  });

  it('uses the resolver from context (per-app CDN base)', () => {
    const resolver = createPortraitResolver('https://cdn.example/images');
    render(
      <PortraitResolverProvider resolver={resolver}>
        <Portrait picture="1001" imageServer={0} size="icon-40" alt="legacy" />
      </PortraitResolverProvider>,
    );
    expect(screen.getByRole('img', { name: 'legacy' })).toHaveAttribute('src', 'https://cdn.example/images/icons/1001.jpg');
  });

  it('never routes managed uploads through a variant directory', () => {
    render(<Portrait picture="a1b2c3d4.png" imageServer={1} size="card" alt="u" />);
    expect(screen.getByRole('img', { name: 'u' })).toHaveAttribute('src', '/d_pic/a1b2c3d4.png');
  });

  it('groups a stack with an accessible label', () => {
    render(
      <PortraitStack label="열람자">
        <Portrait picture="10001" size="icon-20" alt="a" />
        <Portrait picture="10002" size="icon-20" alt="b" />
      </PortraitStack>,
    );
    expect(screen.getByRole('group', { name: '열람자' }).querySelectorAll('.os-portrait')).toHaveLength(2);
  });
});
