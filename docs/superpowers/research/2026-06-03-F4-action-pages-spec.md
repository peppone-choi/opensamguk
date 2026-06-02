# F4 — Action Pages Consolidated Spec

**Date:** 2026-06-03
**Scope:** `web/game` action pages + `app/game-api` read/intake endpoints
**Inputs:** 5 reader reports — diplomacy/board/vote group, board+vote, troop/chief/NPC-control, economy/tournament/inherit/history/simulator, web↔api status matrix. Verified live against `web/game/app/game/*` and `app/game-api/.../controller/*`.

---

## Locked rules (non-negotiable, govern every page below)

1. **Command / short-arg → MODAL.** Any user mutation that maps to a legacy reservable command (or a short arg form: city/general/nation/amount picker) goes through the existing `web/game/components/CommandModal.tsx`. Do **not** hand-roll `api.command('code', {...})` with raw `<input>` per page. The modal already drives off `api.availableCommands(generalId)`, renders the four `command/Select*Field` sub-forms, and posts `POST /api/command/{code}?generalId&turnIdx`. **F4 must retrofit the current ad-hoc pages (auction, betting, diplomacy, nation) to the modal contract.**
2. **game-api = read + precheck + intake only.** Controllers NEVER perform a game-state JPA write. All mutations are submitted via the intake/command path (`CommandController` → Redis XADD → game-engine daemon → `ChangeRecorder`/`JdbcFlushExecutor`). New "write" surfaces (letter send, board post, vote, troop ops, nation policy) are **intake** endpoints that enqueue a command/order; they must not `save()` a domain entity inline. The only acceptable direct persistence is non-game-state social content (board/comment/vote rows) — and even those should land via an intake channel mirroring the betting/auction precedent, not a JPA write inside game-api, to preserve the one-daemon-write rule. See `backend_gaps` for the read/intake split per endpoint.
3. **Design = dark war-room reuse.** Reuse `Shell`, `GameCard`, `GameTable`, `StatusBadge`, `CommandModal`, and the `command/Select*Field` components + existing CSS tokens (`--space-*`, `--text-*`, `--crimson`/`--gold`/`--jade`/`--muted`). No new design system. Matrix/bracket/board layouts follow the legacy structure but rendered with war-room tokens.
4. **Verbatim Korean parity.** Every label, status text, button caption, empty-state, and deny/blocked reason string is reproduced byte-for-byte from the legacy PHP/Vue (e.g. `제안됨`/`승인됨`/`거부됨`/`대체됨`, `송신측의 파기 요청`, `권한이 부족합니다. 수뇌부가 아닙니다.`, `회의실`/`기밀실`, `전력전`/`통솔전`/`일기토`/`설전`, `배당률`/`적중시 환수금`, `16강 상황`, `초깃값으로`/`이전값으로`/`설정`). Deny reasons render as INFO (not error), matching the modal's BLOCKED/UNKNOWN handling.

---

## Live-state corrections vs. the status matrix report

The status matrix is slightly stale. Verified against source:

- **`/api/generals`** — NO `GeneralsController` exists. `api.generals()` (`lib/api.ts:55`) → **404**. The `generals/page.tsx` page renders but its fetch fails. → MISSING (read).
- **`/api/tournament`** — NO `TournamentController` exists. `api.tournament()` (`lib/api.ts:56`) → **404**. Both `tournament/page.tsx` and `tournament-admin/page.tsx` consume it; `tournament-admin` also fires `tournament_start`/`tournament_advance`/`tournament_reset` commands that have **no registered command code**. → MISSING (read + intake).
- **`/api/diplomacy`** — `DiplomacyController` only maps `/api/diplomacy/{nationId}` (state matrix). The frontend `api.diplomacy()` hits **bare** `/api/diplomacy` → **path mismatch / 404**. The matrix endpoint exists; the **letter-management** surface (`getLetter`/`send`/`respond`/`rollback`/`destroy`) and the **conflict** (`분쟁`) feed do not. → PARTIAL.
- **Auction/Betting** read endpoints exist; mutations already correctly route through `CommandController` (`auction_bid`, `bet`) — but via ad-hoc inline inputs, not the modal. → EXISTS (read), needs modal retrofit for parity of the arg form.
- **No controllers at all** for: board (회의실/기밀실), vote (설문), troop (부대 편성), chief center (사령부), NPC control (NPC 정책), nation strategy/finance (내무부), inherit point (유산), history (연감), global diplomacy conflict map. These are net-new pages.

---

## page_plan (per action page)

Below, **backend_status** is one of EXISTS / PARTIAL / MISSING and reflects the *game-api read+intake surface the page needs*, not whether the engine logic exists (most command logic already ships from P2/P5/P6).

| # | Page | route | backend_status | build summary |
|---|------|-------|----------------|----------------|
| 1 | 외교부 (diplomacy letters) | `/game/diplomacy` (split or sub-tab) | PARTIAL | matrix read EXISTS (`/api/diplomacy/{nationId}`); letter list/send/respond/rollback/destroy MISSING. Add `GET /api/diplomacy/letters` (read) + 5 letter ops as **intake** (or a single `che_diplomacy_letter` command family). Retrofit existing inline 종전/불가침/선전 buttons to CommandModal. Render letter cards w/ state text map verbatim. |
| 2 | 중원정보 (global diplomacy + 분쟁 + map) | `/game/global-diplomacy` (new) | PARTIAL | matrix read EXISTS; **conflict (`분쟁`)** feed + per-city conflict% MISSING — add to `GetDiplomacy` response or a `GET /api/diplomacy/conflict`. Map reuses `api.mapPreview()` (EXISTS). Read-only; no mutation. Matrix symbols ★/▲/ㆍ/@ + colors verbatim. |
| 3 | 내무부 (nation strategy/finance) | `/game/nation-finance` (new) | MISSING | Add `GET /api/nation/{id}/finance` (read: gold/rice/income/outcome/policy/warSettingCnt + nationMsg/scoutMsg + editable flag). 7 setters (SetNotice/SetScoutMsg/SetRate/SetBill/SetSecretLimit/SetBlockWar/SetBlockScout) = **intake** commands. TipTap → reuse a textarea (no new rich editor in F4; flag as follow-up). Budget table verbatim labels. |
| 4 | 회의실 / 기밀실 (board) | `/game/board` (new, `?secret=` toggle) | MISSING | Add `GET /api/board?secret=` (read articles+comments), `POST` article + comment as **intake** (social content channel, not JPA write in game-api). Permission gate `checkSecretPermission` ported into precheck. Labels 회의실/기밀실/등록/댓글 달기 + both 권한 차단 strings verbatim. |
| 5 | 설문 조사 (vote) | `/game/vote` (new) | MISSING | Add `GET /api/votes` (list), `GET /api/votes/{id}` (detail+results+myVote), `POST /api/votes/{id}/vote` + comment + `POST /api/votes` (admin) as **intake**. UNIQUE(vote,general) dedupe in engine. `wonLottery` toast. Single/multi-select per `multipleOptions`. All vote labels verbatim. |
| 6 | 부대 편성 (troop) | `/game/troop` (new) | MISSING | Add `GET /api/troops` (read list: leader/members/reservedCommandBrief/turnTime, permission-tiered). 6 ops (NewTroop/JoinTroop/ExitTroop/Disband/Kick/SetTroopName) = **intake** commands. Member popup reuses GeneralBasicCard pattern. 【턴】/【도시】/(N명) format verbatim. |
| 7 | 사령부 (chief center) | `/game/chief-center` (new) | MISSING | Add `GET /api/nation/chief-reserved` (read: 8 chief posts lv 12/11/10/9/8/7/6/5, per-post turn[] up to maxChiefTurn, postFilterNationCommand applied). Reserved-command edits → CommandModal targeting the **nation** command channel (officerLevel ≥ 5 gate). Grid layout reuse. |
| 8 | NPC 정책 (NPC control) | `/game/npc-control` (new) | MISSING | Add `GET /api/nation/npc-policy` (read: default+current policy, chief/general priorities, lastSetters, env). One **intake** endpoint `POST /api/nation/npc-policy` (3 sub-types nationPolicy/nationPriority/generalPriority, `data` JSON). 30+ number fields + 2 draggable priority lists. permission ≥ 1 gate. 초깃값으로/이전값으로/설정 verbatim. Heaviest single page. |
| 9 | 세력 장수 (nation general list) | folds into `/game/generals` or `/game/my-generals` | PARTIAL | Permission-tiered (P0/P1/P2-4) general grid. P0 public fields → needs `GET /api/generals` (see #14). P1/P2 supplement fields come from `api.myGenerals()` (EXISTS) for own nation. Read-only navigate-to-detail. |
| 10 | 경매장 (auction) | `/game/auction` | EXISTS | read EXISTS. Retrofit inline 입찰 `<input>`+`auction_bid` to CommandModal amount sub-form (parity of arg validation + deny strings). Resource vs unique toggle (`isResAuction`). |
| 11 | 베팅장 (betting) | `/game/betting` | EXISTS | read EXISTS (`/api/bettings`). Retrofit inline bet form to CommandModal (`bet`). Add bracket (16강 상황) + odds(배당률)/적중시 환수금 + 4 ranking tables (전력전/통솔전/일기토/설전) — needs tournament read (#12) for bracket. >500원 valid-bet help text verbatim. |
| 12 | 토너먼트 (view) | `/game/tournament` | MISSING | Add `GET /api/tournament` (read: state 0-8, 8 group standings, 16강 bracket, 4 ranking types, tnmt_type, tnmt_msg). Page already coded against this shape — just needs the controller. 참가 (enroll) = **intake** command. |
| 13 | 토너먼트 운영 (admin) | `/game/tournament-admin` | MISSING | Consumes `/api/tournament` (read, #12) + needs ~18 admin **intake** commands (개최/중단/예선/예선전부/추첨/본선/배정/베팅마감/16강/8강/4강/결승/포상/회수/메시지/랜덤투입…). Currently fires unregistered `tournament_start/advance/reset`. Gate userGrade ≥ 5. Lower priority (admin-only). |
| 14 | 전체 장수 (all generals) | `/game/generals` | MISSING | Add `GET /api/generals` (public, no auth, permission=0 fields). Page coded; controller absent → currently 404. Read-only + client search. |
| 15 | 유산 (inherit points) | `/game/inherit` (new) | PARTIAL | Add `GET /api/inherit-point` (read: items/buffs/costs/availableSpecialWar/availableUnique/logs/currentStat + Fibonacci reset-cost). Buy actions (BuyHiddenBuff/BuyRandomUnique/resetTurnTime/resetSpecialWar/…) → CommandModal (BuyHiddenBuff/BuyRandomUnique already registered P6; rest = intake). `nation/page.tsx` already fires BuyHiddenBuff/BuyRandomUnique inline → move to a dedicated inherit page + modal. |
| 16 | 연감 (history) | `/game/history` (new) | MISSING | Add `GET /api/history?serverId&yearMonth` (read: ng_history range + per-month records, map theme). Cross-server view dropped for F4 (single-server) — flag. Read-only viewer. |
| 17 | 전투 시뮬레이터 (battle sim) | `/game/simulator` | EXISTS | `POST /api/simulate-battle` EXISTS. F4: build the full attacker/defender form builder (nation/city/general fields, multi-defender, repeatCnt 1\|1000, seed, import-from-server, JSON save/load) + result panel (avgWar/phase/killed/dead/skills + 2 battle-log HTML panes). Heavy frontend, backend already there. |

---

## backend_gaps (game-api endpoints to add — READ vs INTAKE)

> INTAKE = enqueue a command/order via the intake/command path; NO game-state JPA write in game-api (locked rule 2). Most command *logic* already exists in `logic`/engine from P2/P5/P6 — gaps are the controller seam + (where noted) a new command code registration in the engine dispatcher.

### READ endpoints to add
- `GET /api/generals` — **READ**, public/no-auth, permission=0 general fields. (unblocks page 14 + 9-P0)
- `GET /api/tournament` — **READ**, tournament state/bracket/standings/rankings/msg. (unblocks 12, 13, 11-bracket)
- `GET /api/diplomacy/letters` — **READ**, letter list (nations map + letters + myNationID). (page 1)
- `GET /api/diplomacy/conflict` (or extend `/{nationId}` response) — **READ**, per-city 분쟁% + global matrix. (page 2)
- `GET /api/nation/{id}/finance` — **READ**, gold/rice/income/outcome/policy/warSettingCnt/nationMsg/scoutMsg/editable. (page 3)
- `GET /api/board?secret={bool}` — **READ**, articles+comments, permission-gated. (page 4)
- `GET /api/votes`, `GET /api/votes/{id}` — **READ**, list + detail/results/myVote/userCnt. (page 5)
- `GET /api/troops` — **READ**, troop list permission-tiered. (page 6)
- `GET /api/nation/chief-reserved` — **READ**, 8 chief posts + reserved turns. (page 7)
- `GET /api/nation/npc-policy` — **READ**, default+current policy/priorities/lastSetters/env. (page 8)
- `GET /api/inherit-point` — **READ**, inherit items/buffs/costs/logs/availability/currentStat. (page 15)
- `GET /api/history` — **READ**, ng_history range + records. (page 16)

### INTAKE endpoints / command codes to add (route through CommandController or a dedicated intake controller; no JPA write)
- Diplomacy letters: send / respond / rollback / destroy — **INTAKE** (letter family). (page 1)
- Nation finance setters: SetNotice / SetScoutMsg / SetRate / SetBill / SetSecretLimit / SetBlockWar / SetBlockScout — **INTAKE**. (page 3)
- Board: create article + add comment — **INTAKE** (social-content channel, mirrors betting/auction sink; not a game-api JPA save). (page 4)
- Vote: cast vote / add comment / create poll (admin) — **INTAKE**. (page 5)
- Troop: NewTroop / JoinTroop / ExitTroop / Disband / KickFromTroop / SetTroopName — **INTAKE**. (page 6)
- Chief reserved-command edit — **INTAKE** (nation command channel; some codes already in `TurnDaemonCommandDispatcher` from P6). (page 7)
- NPC policy set (nationPolicy / nationPriority / generalPriority) — **INTAKE**. (page 8)
- Inherit actions beyond BuyHiddenBuff/BuyRandomUnique (resetTurnTime / resetSpecialWar / nextSpecial / bornStatPoint / checkOwner / minSpecificUnique) — **INTAKE**. (page 15)
- Tournament: 참가 (enroll) — **INTAKE**. (page 12)
- Tournament admin (~18 codes: 개최/중단/예선/예선전부/추첨/추첨전부/본선/본선전부/배정/베팅마감/16강/8강/4강/결승/포상/회수/메시지/랜덤투입/랜덤전부투입/무명전부투입/자동개최설정) — **INTAKE**, userGrade ≥ 5. (page 13)
- **Already EXIST as commands (no add, just modal retrofit):** `auction_bid`, `bet`, `BuyHiddenBuff`, `BuyRandomUnique`, `che_종전제의`, `che_불가침파기제의`, `che_불가침제의`, `che_선전포고`.

---

## build_order (wave grouping)

**Wave A — read-only + backend EXISTS (quick parallel, no engine work, disjoint files).**
Each is its own page directory → fully parallel worktrees, no shared-file co-widening.
- A1: **세력 장수 / 전체 장수** read-only polish — but blocked on `/api/generals` (see Wave B); the *page* shell is done.
- A2: **연감 (history)** frontend skeleton against the new read shape (pairs with B's controller).
- A3: **simulator** form builder (page 17) — `/api/simulate-battle` already exists; pure frontend, heavy but self-contained.
- A4: **global-diplomacy (중원정보)** map+matrix view reusing `api.mapPreview()` + existing `/api/diplomacy/{nationId}` — only the 분쟁 feed is missing (degrade gracefully until B lands).
> True quick wins where backend is fully ready: **A3 (simulator)** only. Others need a Wave-B read endpoint first.

**Wave B — new READ endpoints (creator → consumer; controllers are disjoint files, parallel-safe).**
Build the 12 READ controllers/DTOs. Each new controller is a separate file (no co-widening) → parallelizable. Pages from Wave A consume these. Sequence within B: a controller and its consuming page are creator-then-consumer, but distinct pages don't conflict.
- B1 `GeneralsController` → unblocks pages 14, 9-P0.
- B2 `TournamentController` (`GET /api/tournament`) → unblocks 12, 13-read, 11-bracket.
- B3 diplomacy letters + conflict read → pages 1, 2.
- B4 `NationFinanceController` read → page 3.
- B5 `BoardController` read → page 4.
- B6 `VoteController` read → page 5.
- B7 `TroopController` read → page 6.
- B8 `ChiefCenterController` read → page 7.
- B9 `NpcPolicyController` read → page 8.
- B10 `InheritPointController` read → page 15.
- B11 `HistoryController` read → page 16.
> Shared DTO/serializer helpers (e.g. permission-tier field projection, color/状态 text maps) build FIRST as a tiny Tier-0 in B (creator), consumed by the per-controller files (disjoint).

**Wave C — INTAKE / mutation (heavier; engine command-code seam + modal retrofit).**
Order by whether the command code already exists.
- C1 (light, modal retrofit only — codes EXIST): auction (10), betting (11), diplomacy quick-actions (1), inherit Buy* (15-partial). Swap inline inputs → CommandModal. No engine change.
- C2 (intake codes to register, single-general scope): troop ops (6), vote (5), board (4), nation finance setters (3), diplomacy letters (1), inherit resets (15-rest), tournament enroll (12).
- C3 (intake, nation/officer scope, heavier gates): chief reserved edits (7), NPC policy set (8).
- C4 (admin-only, lowest priority): tournament admin ~18 codes (13).

**Cross-wave rule:** within a worktree family, never co-widen the same file. `lib/api.ts` is the one shared file every page touches — its new method additions build in a **single foundation commit at the head of Wave B** (creator), then all pages consume read-only. `CommandModal.tsx` is consume-only in F4 (already built); the `command/Select*Field` set is reused, not widened, except a possible `SelectAmountField` min/max prop extension (one small foundation edit if needed for auction/betting/inherit, done once).

---

## open_questions

1. **Board/vote persistence channel.** Board posts/comments and vote rows are *social content*, not game-state. Locked rule 2 forbids game-api JPA writes. Do we (a) route them through the betting/auction-style intake sink + `ChangeRecorder` betting-channel precedent, or (b) carve an explicit "social content" write exception in game-api? The spec assumes (a) for safety — confirm.
2. **Diplomacy page split.** Legacy has two distinct pages (`t_diplomacy` letters vs `v_globalDiplomacy` matrix/conflict/map). Current `/game/diplomacy` is the matrix+quick-actions. Keep them merged (tabs) or split into `/game/diplomacy` (letters) + `/game/global-diplomacy` (matrix)? Spec proposes split to match legacy.
3. **TipTap rich editor.** Nation finance (nationMsg/scoutMsg) and possibly vote use a TipTap WYSIWYG with base64 image upload (`Misc.UploadImage`). F4 proposes plain textarea + flag rich-editor as a follow-up. Is HTML-markup parity required at F4, or can prose be plaintext for now?
4. **Tournament admin command codes.** ~18 admin commands have no registered code and tie to a god-tier controller (`c_tournament.php`) plus engine-side bracket fns (qualify/finalFight/setGift/...). Is the engine logic already ported (P-?), or does C4 need engine work beyond the controller seam? Likely needs a backlog spec.
5. **`/api/generals` auth surface.** Status matrix says "no auth required (public)". Confirm it returns ONLY permission=0 public fields (no refresh_score, no exp breakdown) so it can be unauthenticated, while the permission-tiered P1/P2 view stays behind `/api/my-generals` (authed).
6. **NPC policy `data` JSON validation.** The single intake endpoint takes `{type, data:JSON.stringify(...)}`. Where does field-range validation (세율 5-30, etc.) live — game-api precheck, or engine? Spec assumes precheck mirrors the legacy `func.php` bounds.
7. **Existing inline-mutation pages.** `diplomacy`, `auction`, `betting`, `nation` currently call `api.command()` directly with a hardcoded `generalId`/`nationId` from a manual `<input>` (e.g. diplomacy's `generalId` number box). The modal resolves caller identity from `front-info`. Retrofit must drop these manual id inputs — confirm front-info identity is reliably available on every action page (it is on the main screen).
8. **History cross-server.** Legacy `v_history` supports viewing other servers' `ng_games`. F4 single-server assumption drops this — confirm acceptable.
