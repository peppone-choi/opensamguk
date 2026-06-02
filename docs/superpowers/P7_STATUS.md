# P7 Status — Read API + Next.js Frontend + SSE

**Branch:** `p7-frontend`  
**Phase goal:** Complete game frontend pages with real-time SSE, API integration, and design system polish.  
**Depends on:** P2 (commands), P6 (auction/betting/diplomacy/messaging/inheritance)

---

## ✅ Completed

### Backend — Read API
| Endpoint | Controller | Status |
|----------|-----------|--------|
| `GET /api/auctions` | `AuctionController` | ✅ |
| `GET /api/auctions/{id}` | `AuctionController` | ✅ |
| `GET /api/bettings` | `BettingController` | ✅ |
| `GET /api/bettings/{id}` | `BettingController` | ✅ |
| `GET /api/mailbox` | `MailboxController` | ✅ |
| `GET /api/mailbox/{id}` | `MailboxController` | ✅ |
| `GET /api/diplomacy` | `DiplomacyController` | ✅ |
| `POST /api/diplomatic-messages/{id}/accept` | `DiplomaticMessageController` | ✅ |
| `POST /api/diplomatic-messages/{id}/decline` | `DiplomaticMessageController` | ✅ |
| `POST /api/command/{code}` | `CommandController` | ✅ |
| `GET /sse/turn` | `RealtimeRelayController` | ✅ |
| `GET /health` | `HealthCheckController` | ✅ |

### Frontend — Page Scaffold
| Page | File | Lines | Features |
|------|------|-------|----------|
| Auction | `web/game/app/game/auction/page.tsx` | 216 | List, bid, SSE refresh, countdown |
| Betting | `web/game/app/game/betting/page.tsx` | 226 | List, place bet, odds display |
| Diplomacy | `web/game/app/game/diplomacy/page.tsx` | 221 | Nation list, relation matrix, proposals |
| Mailbox | `web/game/app/game/mailbox/page.tsx` | 197 | Message list, read/unread, delete |
| Nation | `web/game/app/game/nation/page.tsx` | 261 | Nation info, generals, cities, power |

### Infra
| File | Purpose |
|------|---------|
| `.github/workflows/deploy.yml` | CI/CD: build → GHCR → EC2 deploy |
| `docker-compose.prod.yml` | 8-service prod stack |
| `infra/nginx/nginx.conf` | Reverse proxy + SSE buffering off |
| `scripts/deploy.sh` | EC2 rolling restart |

---

## ⬜ Remaining (P7 Gate)

### Frontend Polish
- [ ] **Design system integration** — color tokens, typography, spacing (Tailwind theme extension)
- [ ] **Shared components** — `<GameCard>`, `<GameTable>`, `<LoadingSpinner>`, `<ErrorBoundary>`, `<Toast>`
- [ ] **Layout shell** — sidebar navigation, header with turn timer, global SSE connection
- [ ] **Auth integration** — JWT token storage, auth-guarded routes, refresh flow
- [ ] **Mobile responsive** — all pages work on 375px+ width
- [ ] **Loading/error states** — skeleton screens, retry logic, offline indicator

### Page-Specific Gaps
- [ ] **Auction** — bid validation UI (min bid, resource check), auction creation form (host-only)
- [ ] **Betting** — real-time odds update via SSE, betting history, reward display
- [ ] **Diplomacy** — proposal creation form, relation change animation, treaty expiry display
- [ ] **Mailbox** — compose message, bulk delete, filter by type
- [ ] **Nation** — general list with pagination, city management, resource graphs

### Integration
- [ ] **Gateway routing** — `/game/*` → `web-game` container via nginx
- [ ] **Auth proxy** — gateway-api JWT validation before game-api calls
- [ ] **SSE reconnect** — exponential backoff, heartbeat/ping
- [ ] **Environment configs** — `.env.local`, `.env.production` for API_BASE

---

## 🔄 P7-P8 Coupled

| Item | P7 Need | P8 Need |
|------|---------|---------|
| Gateway auth | JWT login/logout pages | OAuth2/OIDC integration |
| Gateway profile | User profile page | Full account management |
| Lobby | Server list, game creation | Matchmaking, scenario selection |

---

## Gate Criteria

P7 is "done" when:
1. All 5 game pages are visually polished and mobile-responsive
2. SSE real-time updates work across all pages
3. Auth flow (login → game → logout) is end-to-end
4. Gateway + game-api + game-engine + frontend run together via `docker compose up`
5. No console errors, no unhandled API failures

---

*Last updated: 2026-06-02*
