# P7 Status — Full Game Frontend (All PHP Pages + Command Modals)

**Branch:** `p7-frontend`  
**Phase goal:** Port ALL player-facing PHP pages from `devsam/core` to Next.js + implement command modal system.  
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

### Frontend — Scaffold (5 pages)
| Page | File | Lines | Features |
|------|------|-------|----------|
| Auction | `web/game/app/game/auction/page.tsx` | 216 | List, bid, SSE refresh, countdown |
| Betting | `web/game/app/game/betting/page.tsx` | 226 | List, place bet, odds display |
| Diplomacy | `web/game/app/game/diplomacy/page.tsx` | 221 | Nation list, relation matrix, proposals |
| Mailbox | `web/game/app/game/mailbox/page.tsx` | 197 | Message list, read/unread, delete |
| Nation | `web/game/app/game/nation/page.tsx` | 261 | Nation info, generals, cities, power |

---

## ⬜ Remaining (P7 Gate) — Full PHP Page Port

### My Pages (b_*) — 8 pages
| PHP | Next.js Route | Description | API Need |
|-----|---------------|-------------|----------|
| `b_myPage.php` | `/game` | 내 페이지 (메인 대시보드) | `GET /api/my-page` |
| `b_myGenInfo.php` | `/game/my-generals` | 세력 장수 목록 | `GET /api/my-generals` |
| `b_myCityInfo.php` | `/game/my-cities` | 세력 도시 목록 | `GET /api/my-cities` |
| `b_myBossInfo.php` | `/game/my-boss` | 내 상관 정보 | `GET /api/my-boss` |
| `b_myKingdomInfo.php` | `/game/my-nation` | 내 국가 정보 | `GET /api/my-nation-detail` |
| `b_currentCity.php` | `/game/city` | 현재 도시 정보 | `GET /api/city/{id}` |
| `b_genList.php` | `/game/generals` | 장수 목록 | `GET /api/generals` |
| `b_tournament.php` | `/game/tournament` | 토너먼트 | `GET /api/tournament` |

### Rankings / Stats (a_*) — 8 pages
| PHP | Next.js Route | Description | API Need |
|-----|---------------|-------------|----------|
| `a_bestGeneral.php` | `/game/rankings/best-generals` | 명장 랭킹 | `GET /api/rankings/best-generals` |
| `a_emperior.php` | `/game/rankings/emperor` | 황제 정보 | `GET /api/rankings/emperor` |
| `a_emperior_detail.php` | `/game/rankings/emperor/[id]` | 황제 상세 | `GET /api/rankings/emperor/{id}` |
| `a_genList.php` | `/game/rankings/generals` | 전체 장수 목록 | `GET /api/rankings/generals` |
| `a_kingdomList.php` | `/game/rankings/kingdoms` | 국가 목록 | `GET /api/rankings/kingdoms` |
| `a_npcList.php` | `/game/rankings/npcs` | NPC 목록 | `GET /api/rankings/npcs` |
| `a_hallOfFame.php` | `/game/rankings/hall-of-fame` | 명예의 전당 | `GET /api/rankings/hall-of-fame` |
| `a_traffic.php` | `/game/rankings/traffic` | 트래픽 통계 | `GET /api/rankings/traffic` |

### Others — 2 pages
| PHP | Next.js Route | Description | API Need |
|-----|---------------|-------------|----------|
| `battle_simulator.php` | `/game/simulator` | 전투 시뮬레이터 | `POST /api/simulate-battle` |
| `c_tournament.php` | `/game/tournament-admin` | 토너먼트 관리 | `GET/POST /api/tournament-admin` |

### Command Modal System
| Feature | Description |
|---------|-------------|
| **Command picker** | Grid/list of all ~35 available commands per general |
| **Dynamic form** | Per-command argument form (text, number, select, target picker) |
| **Validation** | Client-side precheck + server-side validation |
| **Submit** | `POST /api/command/{code}` with args |
| **Feedback** | Toast on success/failure, auto-refresh affected page |

---

## ⬜ Remaining — Frontend Foundation

- [ ] **Design system** — CSS custom properties, Pretendard font, dark war-room theme
- [ ] **Shared components** — `GameCard`, `GameTable`, `StatusBadge`, `LoadingSpinner`, `ErrorBoundary`, `Toast`, `CommandModal`, `Sidebar`, `BottomNav`
- [ ] **Layout shell** — header (turn timer + nation badge + resources), nav (sidebar desktop / bottom tabs mobile)
- [ ] **Auth integration** — JWT token, auth-guarded routes, refresh flow
- [ ] **SSE provider** — global EventSource with reconnect, page-aware refresh
- [ ] **Mobile responsive** — desktop-first, adapt to 375px+

---

## ⬜ Remaining — API Gaps

| Endpoint | Needed By | Status |
|----------|-----------|--------|
| `GET /api/my-page` | `/game` | ⬜ |
| `GET /api/my-generals` | `/game/my-generals` | ⬜ |
| `GET /api/my-cities` | `/game/my-cities` | ⬜ |
| `GET /api/my-boss` | `/game/my-boss` | ⬜ |
| `GET /api/my-nation-detail` | `/game/my-nation` | ⬜ |
| `GET /api/city/{id}` | `/game/city` | ⬜ |
| `GET /api/generals` | `/game/generals` | ⬜ |
| `GET /api/tournament` | `/game/tournament` | ⬜ |
| `GET /api/rankings/*` | 8 ranking pages | ⬜ |
| `POST /api/simulate-battle` | `/game/simulator` | ⬜ |
| `GET /api/commands/available` | Command modal | ⬜ |

---

## Gate Criteria

P7 is "done" when:
1. All 22 player-facing pages render matching PHP data structure
2. Command modal works for all ~35 commands with dynamic args
3. SSE real-time updates across all pages
4. Auth flow (login → game → logout) end-to-end
5. Mobile responsive (375px+)
6. Full stack runs via `docker compose up`
7. No console errors

---

*Last updated: 2026-06-02*
