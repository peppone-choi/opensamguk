// Shared TypeScript interfaces for opensamguk game frontend
// Mirrors PHP data structures from devsam/core

export interface General {
  id: number;
  name: string;
  nation: number;
  officerLevel: number;
  owner: number;
  leadership: number;
  strength: number;
  intel: number;
  experience: number;
  devotion: number;
  gold: number;
  rice: number;
  crew: number;
  train: number;
  atmos: number;
  picture: string;
}

export interface City {
  id: number;
  name: string;
  level: number;
  nation: number;
  pop: number;
  agri: number;
  comm: number;
  secu: number;
  def: number;
  wall: number;
  trade: number;
}

export interface Nation {
  id: number;
  name: string;
  color: string;
  level: number;
  gold: number;
  rice: number;
  pop: number;
  genNum: number;
  power: number;
  capital: number;
}

export interface AuctionItem {
  id: number;
  type: string;
  title: string;
  hostName: string;
  amount: number;
  reqResource: string;
  closeDate: string;
  finished: boolean;
  highestBid?: number;
  highestBidder?: string;
}

export interface BettingRound {
  id: number;
  type: string;
  title: string;
  odds: number;
  open: boolean;
  result?: string;
}

export interface DiplomacyRelation {
  nation: number;
  state: string; // 'war', 'peace', 'neutral', 'ally'
  term: number;
}

export interface Message {
  id: number;
  type: string;
  from: string;
  to: string;
  title: string;
  content: string;
  read: boolean;
  createdAt: string;
}

export interface TurnState {
  turn: number;
  year: number;
  month: number;
  turntime: string;
}

export interface CommandArg {
  name: string;
  type: 'text' | 'number' | 'select' | 'target';
  label: string;
  required: boolean;
  options?: { value: string; label: string }[];
  min?: number;
  max?: number;
}

export interface GameCommand {
  code: string;
  name: string;
  category: string;
  args: CommandArg[];
}

// ── F2 Wave 5 — server-driven command catalog (api.availableCommands) ─────────
// Mirrors legacy CommandItem (CommandSelectForm.vue / GetCommandTable):
//   value=command key, simpleName=display, title=tooltip, compensation=-1/0/+1 stat tag,
//   possible=executable-now, reqArg=needs an argument form. argType drives which modal sub-form opens.
// game-api may not emit `argType` yet — when absent we infer it from the command key (ARG_TYPE_BY_KEY).
export type CommandArgType = 'city' | 'general' | 'nation' | 'amount';

export interface AvailableCommand {
  value: string;
  simpleName: string;
  title: string;
  compensation: number;
  possible: boolean;
  reqArg: boolean;
  info?: string;
  argType?: CommandArgType;
}

export interface AvailableCommandCategory {
  category: string;
  values: AvailableCommand[];
}

// game-api response is intentionally loose (W5 endpoint may ship a flat list OR the legacy
// category-grouped `commandTable`). CommandModal normalizes both shapes.
export interface AvailableCommandsResponse {
  result?: boolean;
  commandTable?: AvailableCommandCategory[];
  commands?: AvailableCommand[];
}

export interface MyPageData {
  general: General;
  nation: Nation;
  city: City;
  turn: TurnState;
  notifications: string[];
}

// ── F3 Rankings (game-api RankingController DTOs) ────────────────────────────
// Each interface matches the camelCase JSON the matching /api/rankings/* endpoint
// returns byte-for-byte. The page renders rows in array order and treats
// rank/id as pre-assigned. 재야 = nationId 0 → nation "재야" / nationColor "#000000".

// GET /api/rankings/best-generals → ordered total DESC, tie generalId ASC, incl NPC.
export interface BestGeneral {
  rank: number;
  generalId: number;
  name: string;
  nation: string;
  nationColor: string;
  leadership: number;
  strength: number;
  intel: number;
  total: number; // leadership + strength + intel
}

// GET /api/rankings/generals → default experience DESC; client re-sorts on L/S/I/exp/devotion/crew.
export interface GeneralRank {
  rank: number;
  generalId: number;
  name: string;
  nation: string;
  nationColor: string;
  officerLevel: number;
  leadership: number;
  strength: number;
  intel: number;
  experience: number;
  devotion: number; // = general.dedication
  crew: number;
}

// GET /api/rankings/kingdoms → exclude nationId 0, power DESC. power = SUM(general.crew) proxy.
export interface KingdomRank {
  rank: number;
  nationId: number;
  name: string;
  color: string;
  level: number;
  levelText: string; // 등급 한글명 getNationLevelList()[level][0] (방랑군..천자) — 등급 컬럼에 raw 숫자 대신 표시
  gold: number;
  rice: number;
  pop: number; // SUM(city.pop)
  genNum: number; // count general by nation
  power: number; // SUM(general.crew) by nation (OQ-3 proxy)
  cityCount: number;
  capitalName: string;
}

// GET /api/rankings/npcs → WHERE npc_state=1, total DESC, no rank field.
export interface NpcGeneral {
  generalId: number;
  name: string;
  nation: string;
  nationColor: string;
  officerLevel: number;
  leadership: number;
  strength: number;
  intel: number;
  experience: number;
  devotion: number;
  crew: number;
  cityName: string;
}

// GET /api/rankings/hall-of-fame → F3 default [] (hall empty in 1010).
export interface HallRecord {
  id: number;
  category: string;
  name: string;
  nation: string;
  nationColor: string;
  value: number;
  valueLabel: string;
  achievedAt: string;
  turn: number;
}

// GET /api/rankings/traffic → F3 zero-fill summary, history [] (OQ-2, no online-tracking infra).
export interface TrafficStat {
  date: string;
  uniqueVisitors: number;
  pageViews: number;
  avgSessionMin: number;
  peakConcurrent: number;
}

export interface TrafficSummary {
  todayUnique: number;
  todayViews: number;
  weekUnique: number;
  weekViews: number;
  monthUnique: number;
  monthViews: number;
  peakConcurrent: number;
  currentOnline: number;
  history: TrafficStat[];
}

// GET /api/rankings/emperor → F3 default [] (no unification-history table, OQ-1).
export interface EmperorRecord {
  id: number;
  name: string;
  nation: string;
  nationColor: string;
  unifiedAt: string;
  turn: number;
  year: number;
  month: number;
  generalCount: number;
  cityCount: number;
}

// GET /api/rankings/emperor/{id} → 404 in F3 (no emperior table; page .catch handles).
export interface EmperorDetail {
  id: number;
  name: string;
  nation: string;
  nationColor: string;
  unifiedAt: string;
  turn: number;
  year: number;
  month: number;
  generalCount: number;
  cityCount: number;
  totalGold: number;
  totalRice: number;
  totalPop: number;
  generals: { name: string; leadership: number; strength: number; intel: number }[];
  cities: { name: string; level: number; pop: number }[];
}

// ════════════════════════════════════════════════════════════════════════════
// F4 — Action-page READ contracts (game-api read-only; the page agents consume
// these via the lib/api.ts methods of the same camelCase name). All shapes mirror
// the legacy devsam-core PHP/Vue byte-for-byte (see 2026-06-03-F4-action-pages-spec.md).
//
// LOCKED: READ-ONLY this wave. No mutation/intake shapes here.
// GRACEFUL EMPTY: where scenario_1010 has no backing rows/table (board / vote /
// troop / history / tournament), the controller returns an EMPTY array / zeroed
// shape (200, never 500, never fabricated) — mirroring F3's emperor/traffic
// empty defaults. Interfaces below stay non-optional for the populated shape and
// the page renders the empty array/zero branch.
// Verbatim Korean parity: every label/state string is reproduced byte-for-byte by
// the page; these contracts carry only the structural fields the page maps over.
// ════════════════════════════════════════════════════════════════════════════

// ── page 14 / 9-P0 · 전체 장수 / 세력 장수 (GET /api/generals) ────────────────
// PUBLIC, permission=0 fields only (no refresh_score, no exp breakdown — OQ-5).
// Legacy Nation/GeneralList emits a column-projected [column[], list[][]] form;
// the F4 read endpoint flattens to objects for the public view.
export interface GeneralListItem {
  generalId: number;       // legacy `no`
  name: string;
  nationId: number;        // legacy `nation`
  nationName: string;
  nationColor: string;
  npc: number;             // 0 user / 1 possessed-NPC / 2+ pure NPC
  officerLevel: number;
  officerLevelText: string;
  leadership: number;
  strength: number;
  intel: number;
  experience: number;
  dedication: number;
  injury: number;
  gold: number;
  rice: number;
  crew: number;
  cityId: number;          // legacy `city`
  troopId: number;         // legacy `troop`; 0 = no troop
  picture: string | null;
  imageServer: number;     // legacy `imgsvr`
}

export interface GeneralListResponse {
  result: boolean;
  permission: number;      // caller's secret-permission tier (0 for public/unauth)
  generals: GeneralListItem[];
}

// GET /api/generals 의 실제 응답 행(백엔드 PublicGeneral). 미인증 공개 surface라 raw exp/ded·gold/rice는
// 없고, 명성/계급은 레거시처럼 **레벨 버킷**으로 내려온다(explevel/honorText, dedlevel/dedLevelText/bill).
// 응답은 이 행들의 **bare 배열**(래퍼 { generals } 아님).
export interface PublicGeneral {
  generalId: number;
  name: string;
  nationId: number;        // 0 = 재야
  nationName: string;
  nationColor: string;
  npc: number;             // 0 user / 1 possessed-NPC / 2+ pure NPC
  officerLevel: number;
  officerLevelText: string;
  leadership: number;
  strength: number;
  intel: number;
  explevel: number;        // 명성 레벨 버킷(getExpLevel) — 명성 표시·정렬 키
  honorText: string;       // 명성 칭호(getHonor)
  dedlevel: number;        // 계급 레벨 버킷(getDedLevel) — 계급 정렬 키
  dedLevelText: string;    // 계급 한글명(getDedLevelText)
  bill: number;            // 봉록(getBillByLevel)
  crew: number;
  cityName: string;
}

// ── page 12/13/11-bracket · 토너먼트 (GET /api/tournament) ────────────────────
// state 0-8 (phase), tnmt_type 전력전/통솔전/일기토/설전, 8 group standings, 16강
// bracket, 4 ranking types. EMPTY/zeroed when no tournament is active in 1010.
export type TournamentTypeText = '전력전' | '통솔전' | '일기토' | '설전';

export interface TournamentEntrant {
  generalId: number;
  generalName: string;
  nationName: string;
  nationColor: string;
  win: number;
  draw: number;
  lose: number;
  group: number;           // 0-7 (예선 8개 조)
  groupRank: number;
}

// 16강 single-elimination bracket; one node per match.
export interface TournamentBracketMatch {
  round: number;           // 16/8/4/2 (강) — higher = earlier
  matchIdx: number;
  leftGeneralId: number | null;
  leftName: string | null;
  rightGeneralId: number | null;
  rightName: string | null;
  winnerGeneralId: number | null;
}

// 전력전/통솔전/일기토/설전 ranking row (4 ranking types).
export interface TournamentRankRow {
  rank: number;
  generalId: number;
  generalName: string;
  nationName: string;
  value: number;
}

export interface TournamentResponse {
  result: boolean;
  state: number;           // legacy gameStor `tournament` phase 0-8
  tnmtType: number;        // legacy `tnmt_type` raw code
  tnmtTypeText: TournamentTypeText;
  tnmtMsg: string;         // 운영자 메세지 (verbatim, may be '')
  entrants: TournamentEntrant[];      // 8 group standings flattened ([] when none)
  bracket: TournamentBracketMatch[];  // 16강 상황 ([] until drawn)
  rankings: {              // 4 ranking tables; each [] when no data
    total: TournamentRankRow[];        // 전력전 (종합)
    leadership: TournamentRankRow[];   // 통솔전 (통솔)
    strength: TournamentRankRow[];     // 일기토 (무력)
    intel: TournamentRankRow[];        // 설전 (지력)
  };
}

// ── page 1 · 외교부 (GET /api/diplomacy/letters) ──────────────────────────────
// Mirrors j_diplomacy_get_letter.php. State text map rendered by page verbatim:
// 제안됨/승인됨/거부됨/대체됨, 송신측의 파기 요청 …. text_detail masked to
// '(권한이 부족합니다)' when permission<3 (game-api precheck).
export interface DiplomacyLetterParty {
  nationId: number;        // legacy `nationID`
  name: string;
  color: string;
}

export interface DiplomacyLetter {
  no: number;
  src: DiplomacyLetterParty;
  dest: DiplomacyLetterParty;
  prevNo: number | null;   // legacy `prev_no`
  state: string;           // proposed/accepted/declined/replaced/… (rendered verbatim)
  stateOpt: string | null; // legacy `state_opt`
  brief: string;           // legacy `text_brief`
  detail: string;          // legacy `text_detail` (may be '(권한이 부족합니다)')
  date: string;
}

export interface DiplomacyLetterNation {
  nationId: number;
  name: string;
  color: string;
  level: number;
}

export interface DiplomacyLettersResponse {
  result: boolean;
  nations: DiplomacyLetterNation[];   // candidate counter-nations (excludes self & 재야)
  letters: DiplomacyLetter[];         // newest-first; [] when none
  myNationId: number;                 // legacy `myNationID`
}

// ── page 2 · 중원정보 (GET /api/diplomacy/conflict) ───────────────────────────
// Mirrors Global/GetDiplomacy.php. Matrix symbols ★/▲/ㆍ/@ + colors rendered by
// page verbatim. `conflict` is per-city 분쟁 share (%); diplomacyList masks
// neutral states 3-7 → 2 for nations not involving the viewer.
export interface ConflictNation {
  nationId: number;        // legacy `nation`
  name: string;
  color: string;
  level: number;
  power: number;
  cities: string[];        // city names owned (insertion order preserved)
}

// [cityId, { nationId: sharePct }] — share rounded to 1 dp (PhpRound half-away).
export type ConflictCity = [number, Record<number, number>];

export interface DiplomacyConflictResponse {
  result: boolean;
  nations: ConflictNation[];               // active nations (level>0), power DESC
  conflict: ConflictCity[];                // [] when no contested cities
  diplomacyList: Record<number, Record<number, number>>; // {me:{you:stateCode}}
  myNationId: number;
}

// ── page 3 · 내무부 (GET /api/nation/{id}/finance) ────────────────────────────
// Mirrors v_nationStratFinan.php. Budget table labels rendered verbatim.
// editable = (officerLevel>=5 || permission==4). nationMsg/scoutMsg are plaintext
// in F4 (TipTap rich editor deferred — spec OQ-3).
export interface NationFinancePolicy {
  rate: number;            // 세율 (5-30)
  bill: number;            // 지급률
  secretLimit: number;     // 기밀 사관 제한
  blockScout: boolean;     // 등용 차단 (legacy `scout` != 0)
  blockWar: boolean;       // 전쟁 차단 (legacy `war` != 0)
}

export interface NationFinanceIncome {
  gold: { city: number; war: number };
  rice: { city: number; wall: number };
}

export interface NationFinanceWarSettingCnt {
  remain: number;
  inc: number;
  max: number;
}

export interface NationFinanceResponse {
  result: boolean;
  editable: boolean;
  nationId: number;
  officerLevel: number;
  year: number;
  month: number;
  nationMsg: string;       // 국가 공지 (plaintext)
  scoutMsg: string;        // 등용 메시지 (plaintext)
  gold: number;
  rice: number;
  income: NationFinanceIncome;
  outcome: number;         // 인건비 지출
  policy: NationFinancePolicy;
  warSettingCnt: NationFinanceWarSettingCnt;
}

// ── page 7 · 사령부 (GET /api/nation/chief-reserved) ──────────────────────────
// Mirrors game-api ChiefReservedResponse (dto/F4Dto.kt). The 8 chief posts ride the
// `posts[]` array (NOT a map), each holding a reserved-command `reservedTurns[]` up to
// maxChiefTurn. `commandList` is the chief command palette (getChiefCommandTable).
// officerLevel>=5(=myOfficerLevel) gate to edit. POST reserve rides nation_turn ring.
export interface ChiefReservedTurn {
  turnIdx: number;         // 예약 슬롯 인덱스
  actionCode: string;      // command class key
  brief: string;           // rendered verbatim (color/tag markup 포함)
  arg: Record<string, unknown> | null;
}

export interface ChiefPost {
  officerLevel: number;    // 12/11/10/9/8/7/6/5
  title: string;           // 정본 직책명(군주/참모/…)
  name: string | null;     // occupant general name (null = vacant)
  turnTime: string | null;
  npcType: number | null;
  officerLevelText: string;
  reservedTurns: ChiefReservedTurn[];
}

// 사령부 명령 팔레트의 1개 명령(getChiefCommandTable values[]). argType는 game-api가
// argsSchema 키에서 파생(city/nation/general/amount); 인자 없으면 null.
export interface ChiefCommand {
  value: string;           // 예약 액션 코드(e.g. "che_급습")
  simpleName: string;
  title: string;
  compensation: number;
  possible: boolean;       // 실제 precheck 결과(deny면 false) — AvailableCommand.possible와 동일
  reqArg: boolean;
  argType: CommandArgType | null;
  reason?: string | null;  // deny 사유(possible=false일 때) — 임파서블 명령 툴팁에 노출
}

// 1개 카테고리(휴식/인사/외교/특수/전략/기타).
export interface ChiefCommandCategory {
  category: string;
  values: ChiefCommand[];
}

export interface ChiefReservedResponse {
  result: boolean;
  myGeneralId: number;     // 호출자(나)의 장수 id
  myOfficerLevel: number;  // 호출자(나)의 officer_level
  nationId: number;
  nationName: string | null;
  nationLevel: number;
  year: number;
  month: number;
  turnTerm: number;
  maxChiefTurn: number;
  posts: ChiefPost[];
  troopList: Record<string, string>;        // troopLeaderId → troopName
  commandList: ChiefCommandCategory[];
  isChief: boolean;
  autorunLimit: number | null;
}

// ── page 8 · NPC 정책 (GET /api/nation/npc-policy) ────────────────────────────
// Mirrors v_NPCControl.php. Heaviest page: 30+ number policy fields + 2 draggable
// priority lists. 초깃값으로/이전값으로/설정 rendered verbatim. permission>=1 gate.
// Policy values are an open numeric map (legacy AutorunNationPolicy::$defaultPolicy).
export interface NpcPolicyLastSetter {
  setter: string | null;
  date: string | null;
}

export interface NpcPolicyResponse {
  result: boolean;
  nationId: number;
  defaultNationPolicy: Record<string, number>;   // server defaults + class defaults
  currentNationPolicy: Record<string, number>;   // nation overrides folded on default
  zeroPolicy: Record<string, number>;            // 초깃값으로 reset target
  defaultNationPriority: string[];
  currentNationPriority: string[];
  availableNationPriorityItems: string[];
  defaultGeneralActionPriority: string[];
  currentGeneralActionPriority: string[];
  availableGeneralActionPriorityItems: string[];
  lastSetters: {
    policy: NpcPolicyLastSetter;
    nation: NpcPolicyLastSetter;
    general: NpcPolicyLastSetter;
  };
  defaultStatNPCMax: number;
  defaultStatMax: number;
}

// ── page 15 · 유산 (GET /api/inherit-point) ───────────────────────────────────
// Mirrors v_inheritPoint.php. items keyed by InheritanceKey; reset costs follow a
// Fibonacci base (resetTurnTime/resetSpecialWar). availableSpecialWar/availableUnique
// are catalog maps. logs = last 30 inheritPoint user_record rows.
export interface InheritSpecialWar {
  title: string;
  info: string;
}

export interface InheritUnique {
  title: string;
  rawName: string;
  info: string;
}

export interface InheritActionCost {
  buff: number[];          // GameConst inheritBuffPoints (per-step)
  resetTurnTime: number;   // Fibonacci(resetTurnTimeLevel)
  resetSpecialWar: number; // Fibonacci(resetSpecialWarLevel)
  randomUnique: number;
  nextSpecial: number;
  minSpecificUnique: number;
  checkOwner: number;
  bornStatPoint: number;
}

export interface InheritPointLog {
  id: number;
  serverId: string;        // legacy `server_id`
  year: number;
  month: number;
  date: string;
  text: string;            // rendered verbatim
}

export interface InheritCurrentStat {
  leadership: number;
  strength: number;
  intel: number;
  statMin: number;
  statMax: number;
}

export interface InheritPointResponse {
  result: boolean;
  items: Record<string, number>;                 // InheritanceKey → point balance
  currentInheritBuff: Record<string, number>;     // buffKey → level
  maxInheritBuff: number;
  resetTurnTimeLevel: number;
  resetSpecialWarLevel: number;
  inheritActionCost: InheritActionCost;
  availableSpecialWar: Record<string, InheritSpecialWar>;
  availableUnique: Record<string, InheritUnique>;
  lastInheritPointLogs: InheritPointLog[];         // [] when none
  availableTargetGeneral: Record<number, string>;  // generalId → name (npc<2)
  currentStat: InheritCurrentStat;
}

// ── page 4 · 회의실 / 기밀실 (GET /api/board?secret=) ─────────────────────────
// Mirrors j_board_get_articles.php. Labels 회의실/기밀실/등록/댓글 달기 verbatim.
// Permission gates render as INFO (not error): '국가에 소속되어있지 않습니다.' /
// '권한이 부족합니다. 수뇌부가 아닙니다.'. EMPTY articles[] when no rows.
// 필드명은 game-api DTO를 그대로 미러링한다 (app/game-api .../dto/F4Dto.kt BoardResponse/
// BoardArticle/BoardComment) — 프록시가 pass-through라 페이지가 이 이름을 그대로 소비한다.
export interface BoardComment {
  id: number;
  authorGeneralId: number;
  authorName: string;
  text: string;
  date: string;            // ISO instant
}

export interface BoardArticle {
  id: number;
  nationId: number;
  authorGeneralId: number;
  authorName: string;
  title: string;
  contentHtml: string;
  date: string;            // ISO instant
  comments: BoardComment[]; // 시간순 오름차순
}

export interface BoardResponse {
  result: boolean;
  secret: boolean;                   // true → 기밀실, false → 회의실
  title: string;                     // 그대로 회의실 / 기밀실
  articles: BoardArticle[];          // 최신순; 없으면 []
  blockedReason: string | null;      // 권한 게이트가 기밀 방을 차단했을 때 설정 (INFO)
}

// ── page 5 · 설문 조사 (GET /api/votes, GET /api/votes/{id}) ──────────────────
// Mirrors Vote/GetVoteList + GetVoteDetail. multipleOptions drives single/multi
// select. wonLottery toast handled by intake (not F4 read). EMPTY votes[] in 1010.
export interface VoteInfo {
  id: number;
  title: string;
  multipleOptions: number; // 0 single / N max selections
  opener: string | null;
  startDate: string;
  endDate: string | null;
  options: string[];       // rendered verbatim
}

export interface VoteListResponse {
  result: boolean;
  votes: Record<number, VoteInfo>;   // keyed by voteId; {} when none
}

export interface VoteComment {
  id: number | null;
  voteID: number;          // legacy RawName vote_id
  generalID: number;
  nationID: number;
  nationName: string;
  generalName: string;
  text: string;
  date: string;
}

// [selectionArray, count] — selection is the decoded option-index array.
export type VoteResultRow = [number[], number];

export interface VoteDetailResponse {
  result: boolean;
  voteInfo: VoteInfo;
  votes: VoteResultRow[];  // tallies grouped by selection; [] when none
  comments: VoteComment[];
  myVote: number[] | null; // caller's selection (null if not voted / not logged in)
  userCnt: number;         // total eligible voters (general npc<2)
}

// ── page 6 · 부대 편성 (GET /api/troops) ──────────────────────────────────────
// Mirrors PageTroop.vue TroopInfo (built from Nation/GeneralList troops + members).
// 【턴】/【도시】/(N명) format rendered verbatim. Permission-tiered. EMPTY [] when none.
export interface TroopInfo {
  troopId: number;         // = troop leader generalId
  troopName: string;
  troopLeader: GeneralListItem;
  turnTime: string;
  reservedCommandBrief: string[];  // '집합' or '-' per turn slot (verbatim)
  members: GeneralListItem[];
}

export interface TroopListResponse {
  result: boolean;
  permission: number;
  troops: TroopInfo[];     // [] when no troops formed
  myGeneralId: number;
}

// ── page 16 · 연감 (GET /api/history?yearMonth=) ──────────────────────────────
// Mirrors v_history.php + Global/GetHistory.php. Single-server only in F4
// (cross-server dropped — OQ-8). yearMonth = Util::joinYearMonth; selectable
// range [firstYearMonth, lastYearMonth]. EMPTY/null record when no ng_history rows.
export interface HistoryRecord {
  serverId: string;        // legacy `server_id`
  year: number;
  month: number;
  globalHistory: string[]; // legacy `global_history` (decoded; rendered verbatim)
  globalAction: string[];  // legacy `global_action`
  nations: unknown[];      // legacy `nations` snapshot (page maps for the map theme)
  map: unknown;            // legacy `map` snapshot
  hash: string;
}

export interface HistoryResponse {
  result: boolean;
  firstYearMonth: number;
  lastYearMonth: number;
  currentYearMonth: number;
  serverId: string;
  mapName: string;
  record: HistoryRecord | null;   // selected month; null when range empty
}
