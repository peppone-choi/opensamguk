# P7 Frontend Design Spec

**Date:** 2026-06-02  
**Branch:** `p7-frontend`  
**Scope:** All 5 game pages (auction, betting, diplomacy, mailbox, nation)  
**Approach:** CSS Custom Properties + Vanilla Components (no Tailwind, no UI library)

---

## 1. Visual Thesis

Dark strategic war-room. Ink-black surfaces with gold and crimson accents. Dense information hierarchy reminiscent of a military command center. Every pixel serves game state awareness. No decorative noise.

## 2. Content Plan

### Shared Shell
- **Header** — turn timer (countdown to next turn), nation badge, gold/rice summary, SSE connection indicator
- **Navigation** — sidebar (desktop ≥1024px) / bottom tab bar (mobile <1024px)
- **Toast** — global notification stack (bottom-right desktop, top mobile)
- **Error Boundary** — graceful degradation with retry button

### Page Breakdown

| Page | Primary Content | Secondary Content | Actions |
|------|----------------|-------------------|---------|
| **Auction** | Active auction list (cards) | Finished auction history | Bid input, refresh |
| **Betting** | Open betting rounds | Closed rounds with results | Place bet, view odds |
| **Diplomacy** | Nation relation matrix | Pending proposals | Accept/decline, propose |
| **Mailbox** | Message list (unread first) | Message detail view | Delete, mark read |
| **Nation** | Nation overview (power, generals, cities) | Resource graphs | — |

## 3. Design Tokens (CSS Custom Properties)

```css
:root {
  /* Surfaces */
  --bg-base: #0a0a0a;
  --bg-elevated: #141414;
  --bg-card: #1a1a1a;
  --bg-hover: #222222;
  --bg-active: #2a2a2a;

  /* Accents */
  --gold: #c9a227;
  --gold-dim: #8a7020;
  --crimson: #c62828;
  --crimson-dim: #8b1a1a;
  --jade: #2e7d32;
  --jade-dim: #1b5e20;

  /* Text */
  --text-primary: #f0f0f0;
  --text-secondary: #a0a0a0;
  --text-muted: #666666;
  --text-inverse: #0a0a0a;

  /* Borders */
  --border-subtle: #333333;
  --border-medium: #444444;
  --border-accent: var(--gold);

  /* Spacing */
  --space-xs: 0.25rem;   /* 4px */
  --space-sm: 0.5rem;    /* 8px */
  --space-md: 1rem;      /* 16px */
  --space-lg: 1.5rem;    /* 24px */
  --space-xl: 2rem;      /* 32px */
  --space-2xl: 3rem;     /* 48px */

  /* Typography */
  --font-sans: 'Pretendard', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
  --text-xs: 0.75rem;
  --text-sm: 0.875rem;
  --text-base: 1rem;
  --text-lg: 1.125rem;
  --text-xl: 1.25rem;
  --text-2xl: 1.5rem;
  --text-3xl: 2rem;

  /* Radius */
  --radius-sm: 4px;
  --radius-md: 8px;
  --radius-lg: 12px;

  /* Shadows */
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.3);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.4);
  --shadow-gold: 0 0 12px rgba(201,162,39,0.2);

  /* Z-index */
  --z-base: 0;
  --z-sticky: 100;
  --z-toast: 200;
  --z-modal: 300;

  /* Transitions */
  --transition-fast: 150ms ease;
  --transition-base: 250ms ease;
}
```

## 4. Component Design

### GameCard
- Background: `--bg-card`
- Border: 1px solid `--border-subtle`
- Padding: `--space-md`
- Border-radius: `--radius-md`
- Hover: border-color → `--border-accent`, `box-shadow: var(--shadow-gold)`
- Transition: `--transition-base`

### GameTable
- Header row: `--bg-hover`, bold text
- Data rows: alternating `--bg-card` / `--bg-base`
- Cell padding: `--space-sm` vertical, `--space-md` horizontal
- Border: 1px solid `--border-subtle` between rows

### StatusBadge
- Variants: `gold` (accent), `crimson` (danger), `jade` (success), `muted` (info)
- Pill shape: `--radius-sm`, padding `--space-xs` `--space-sm`
- Font: `--text-xs`, uppercase

### Toast
- Position: fixed bottom-right (desktop), top-center (mobile)
- Background: `--bg-elevated` with `--border-accent` left border
- Auto-dismiss: 3s, slide-in animation

## 5. Interaction Plan

1. **Page entrance**: staggered fade-in for cards (0.08s delay per item, 0.3s duration)
2. **SSE data refresh**: subtle gold border pulse on updated cards (0.5s, one-shot)
3. **Card hover**: lift 2px + gold shadow glow
4. **Button press**: scale 0.98 + darker background
5. **Loading skeleton**: shimmer animation on `--bg-hover` to `--bg-card` gradient

## 6. Responsive Strategy

**Desktop-first** (per user choice), adapting down to mobile.

| Breakpoint | Layout |
|------------|--------|
| ≥1280px | Sidebar (240px fixed) + main content area |
| 1024–1279px | Collapsible sidebar (icon-only) + main content |
| 768–1023px | Bottom tab bar + stacked cards |
| <768px | Bottom tab bar + single column, full-width cards |

## 7. Animation Spec

```css
/* Entrance */
@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

/* SSE pulse */
@keyframes goldPulse {
  0%, 100% { border-color: var(--border-subtle); }
  50% { border-color: var(--gold); box-shadow: var(--shadow-gold); }
}

/* Shimmer */
@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}
```

## 8. File Structure

```
web/game/
├── app/
│   ├── globals.css          /* design tokens + animations + base */
│   ├── layout.tsx           /* shell: nav + header + SSE provider */
│   ├── page.tsx             /* lobby / redirect */
│   ├── game/
│   │   ├── auction/page.tsx
│   │   ├── betting/page.tsx
│   │   ├── diplomacy/page.tsx
│   │   ├── mailbox/page.tsx
│   │   └── nation/page.tsx
│   └── api/
│       └── health/route.ts
├── components/
│   ├── GameCard.tsx
│   ├── GameTable.tsx
│   ├── StatusBadge.tsx
│   ├── Toast.tsx
│   ├── ToastProvider.tsx
│   ├── SkeletonCard.tsx
│   ├── Shell.tsx
│   ├── Header.tsx
│   ├── Sidebar.tsx
│   ├── BottomNav.tsx
│   └── ErrorBoundary.tsx
├── hooks/
│   ├── useSSE.ts            /* EventSource with reconnect */
│   ├── useApi.ts            /* fetch with error handling */
│   └── useToast.ts
├── lib/
│   ├── api.ts               /* API client */
│   ├── format.ts            /* number/date/formatRemaining */
│   └── constants.ts         /* API_BASE, labels */
├── types/
│   └── game.ts              /* shared TypeScript interfaces */
└── public/
```

## 9. Dependencies

```json
{
  "dependencies": {
    "next": "15.1.3",
    "react": "19.0.0",
    "react-dom": "19.0.0"
  },
  "devDependencies": {
    "typescript": "5.7.2",
    "@types/node": "22.10.2",
    "@types/react": "19.0.2",
    "@types/react-dom": "19.0.2"
  }
}
```

No Tailwind. No UI library. Pure CSS custom properties + React.

## 10. API Integration Pattern

```ts
// lib/api.ts
const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json();
}

async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json();
}
```

## 11. Accessibility

- All interactive elements have visible focus states (`outline: 2px solid var(--gold)`)
- Color contrast ≥ WCAG AA (text-primary on bg-card = 12.5:1)
- Semantic HTML: `nav`, `main`, `section`, `article`, `button`
- Reduced motion: `@media (prefers-reduced-motion: reduce)` disables animations

---

*Spec self-review: No TBD placeholders. No contradictions. Scope = 5 pages + shared shell. Ambiguity: none.*
