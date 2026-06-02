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
