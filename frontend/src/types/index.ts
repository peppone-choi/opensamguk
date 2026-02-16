// Auth types
export interface User {
  id: number;
  loginId: string;
  displayName: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

// World
export interface WorldState {
  id: number;
  scenarioCode: string;
  currentYear: number;
  currentMonth: number;
  tickSeconds: number;
  realtimeMode: boolean;
  commandPointRegenRate: number;
  config: Record<string, unknown>;
  meta: Record<string, unknown>;
  updatedAt: string;
}

// Nation
export interface Nation {
  id: number;
  worldId: number;
  name: string;
  color: string;
  capitalCityId: number | null;
  gold: number;
  rice: number;
  bill: number;
  rate: number;
  rateTmp: number;
  secretLimit: number;
  chiefGeneralId: number;
  scoutLevel: number;
  warState: number;
  strategicCmdLimit: number;
  surrenderLimit: number;
  tech: number;
  power: number;
  level: number;
  typeCode: string;
  spy: Record<string, unknown>;
  meta: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

// City
export interface City {
  id: number;
  worldId: number;
  name: string;
  level: number;
  nationId: number;
  supplyState: number;
  frontState: number;
  pop: number;
  popMax: number;
  agri: number;
  agriMax: number;
  comm: number;
  commMax: number;
  secu: number;
  secuMax: number;
  trust: number;
  trade: number;
  dead: number;
  def: number;
  defMax: number;
  wall: number;
  wallMax: number;
  officerSet: number;
  state: number;
  region: number;
  term: number;
  conflict: Record<string, unknown>;
  meta: Record<string, unknown>;
}

// General (5-stat system)
export interface General {
  id: number;
  worldId: number;
  userId: number | null;
  name: string;
  nationId: number;
  cityId: number;
  troopId: number;
  npcState: number;
  affinity: number;
  bornYear: number;
  deadYear: number;
  leadership: number;
  leadershipExp: number;
  strength: number;
  strengthExp: number;
  intel: number;
  intelExp: number;
  politics: number;
  charm: number;
  injury: number;
  experience: number;
  dedication: number;
  officerLevel: number;
  officerCity: number;
  gold: number;
  rice: number;
  crew: number;
  crewType: number;
  train: number;
  atmos: number;
  weaponCode: string;
  bookCode: string;
  horseCode: string;
  itemCode: string;
  turnTime: string;
  recentWarTime: string | null;
  makeLimit: number;
  killTurn: number | null;
  age: number;
  startAge: number;
  belong: number;
  betray: number;
  personalCode: string;
  specialCode: string;
  specAge: number;
  special2Code: string;
  spec2Age: number;
  commandPoints: number;
  commandEndTime: string | null;
  lastTurn: Record<string, unknown>;
  meta: Record<string, unknown>;
  penalty: Record<string, unknown>;
  picture: string;
  defenceTrain: number;
  tournamentState: number;
  blockState: number;
  permission: string;
  imageServer: number;
  dedLevel: number;
  expLevel: number;
  createdAt: string;
  updatedAt: string;
}

// Troop
export interface Troop {
  id: number;
  worldId: number;
  leaderGeneralId: number;
  nationId: number;
  name: string;
  meta: Record<string, unknown>;
  createdAt: string;
}

// Diplomacy
export interface Diplomacy {
  id: number;
  worldId: number;
  srcNationId: number;
  destNationId: number;
  stateCode: string;
  term: number;
  isDead: boolean;
  isShowing: boolean;
}

// Message
export interface Message {
  id: number;
  worldId: number;
  mailboxCode: string;
  messageType: string;
  srcId: number | null;
  destId: number | null;
  sentAt: string;
  validUntil: string | null;
  payload: Record<string, unknown>;
  meta: Record<string, unknown>;
}

// Command types
export interface GeneralTurn {
  id: number;
  generalId: number;
  turnIdx: number;
  actionCode: string;
  arg: Record<string, unknown>;
  brief: string | null;
}

export interface NationTurn {
  id: number;
  nationId: number;
  officerLevel: number;
  turnIdx: number;
  actionCode: string;
  arg: Record<string, unknown>;
  brief: string | null;
}

export interface CommandResult {
  success: boolean;
  logs: string[];
  message?: string;
}

export interface RealtimeStatus {
  generalId: number;
  commandPoints: number;
  commandEndTime: string | null;
  remainingSeconds: number;
}

// Scenario
export interface Scenario {
  code: string;
  title: string;
  startYear: number;
}

// Map
export interface CityConst {
  id: number;
  name: string;
  level: number;
  region: number;
  x: number;
  y: number;
  connections: number[];
}

export interface MapData {
  cities: CityConst[];
}

// FrontInfo (main dashboard API response) — legacy parity
export interface FrontInfoResponse {
  global: GlobalInfo;
  general: GeneralFrontInfo | null;
  nation: NationFrontInfo | null;
  city: CityFrontInfo | null;
  recentRecord: RecentRecordInfo;
  aux: AuxInfo;
}

export interface AuxInfo {
  myLastVote?: number | null;
}

export interface GlobalInfo {
  year: number;
  month: number;
  turnTerm: number;
  startyear: number;
  genCount: number[][];
  onlineNations: OnlineNationInfo[];
  onlineUserCnt: number;
  auctionCount: number;
  tournamentState: number;
  tournamentType?: number | null;
  tournamentTime?: string | null;
  isTournamentActive: boolean;
  isTournamentApplicationOpen: boolean;
  isBettingActive: boolean;
  lastExecuted: string | null;
  isLocked: boolean;
  scenarioText: string;
  realtimeMode: boolean;
  extendedGeneral: number;
  isFiction: number;
  npcMode: number;
  joinMode: string;
  develCost: number;
  noticeMsg: number;
  apiLimit: number;
  generalCntLimit: number;
  serverCnt: number;
  lastVoteID: number;
  lastVote: unknown;
}

export interface OnlineNationInfo {
  id: number;
  name: string;
  color: string;
  genCount: number;
}

export interface NationTypeInfo {
  raw: string;
  name: string;
  pros: string;
  cons: string;
}

export interface TopChiefInfo {
  officerLevel: number;
  no: number;
  name: string;
  npc: number;
}

export interface NationPopulationInfo {
  cityCnt: number;
  now: number;
  max: number;
}

export interface NationCrewInfo {
  generalCnt: number;
  now: number;
  max: number;
}

export interface NationNoticeInfo {
  date: string;
  msg: string;
  author: string;
  authorID: number;
}

export interface TroopInfoFront {
  leader: { city: number; reservedCommand: unknown };
  name: string;
}

export interface GeneralFrontInfo {
  no: number;
  name: string;
  picture: string;
  imgsvr: number;
  nation: number;
  npc: number;
  city: number;
  troop: number;
  officerLevel: number;
  officerLevelText: string;
  officerCity: number;
  permission: number;
  lbonus: number;
  leadership: number;
  leadershipExp: number;
  strength: number;
  strengthExp: number;
  intel: number;
  intelExp: number;
  politics: number;
  charm: number;
  experience: number;
  dedication: number;
  explevel: number;
  dedlevel: number;
  honorText: string;
  dedLevelText: string;
  bill: number;
  gold: number;
  rice: number;
  crew: number;
  crewtype: string;
  train: number;
  atmos: number;
  weapon: string;
  book: string;
  horse: string;
  item: string;
  personal: string;
  specialDomestic: string;
  specialWar: string;
  specage: number;
  specage2: number;
  age: number;
  injury: number;
  killturn: number | null;
  belong: number;
  betray: number;
  blockState: number;
  defenceTrain: number;
  turntime: string;
  recentWar: string | null;
  commandPoints: number;
  commandEndTime: string | null;
  ownerName: string | null;
  refreshScoreTotal: number | null;
  refreshScore: number | null;
  autorunLimit: number;
  reservedCommand: unknown;
  troopInfo: TroopInfoFront | null;
  dex1: number;
  dex2: number;
  dex3: number;
  dex4: number;
  dex5: number;
  warnum: number;
  killnum: number;
  deathnum: number;
  killcrew: number;
  deathcrew: number;
  firenum: number;
}

export interface NationFrontInfo {
  id: number;
  full: boolean;
  name: string;
  color: string;
  level: number;
  type: NationTypeInfo;
  gold: number;
  rice: number;
  tech: number;
  power: number;
  gennum: number;
  capital: number | null;
  bill: number;
  taxRate: number;
  population: NationPopulationInfo;
  crew: NationCrewInfo;
  onlineGen: string;
  notice: NationNoticeInfo | null;
  topChiefs: Record<number, TopChiefInfo | null>;
  diplomaticLimit: number;
  strategicCmdLimit: number;
  impossibleStrategicCommand: unknown[];
  prohibitScout: number;
  prohibitWar: number;
}

export interface CityNationInfo {
  id: number;
  name: string;
  color: string;
}

export interface CityFrontInfo {
  id: number;
  name: string;
  level: number;
  nationInfo: CityNationInfo;
  trust: number;
  pop: [number, number];
  agri: [number, number];
  comm: [number, number];
  secu: [number, number];
  def: [number, number];
  wall: [number, number];
  trade: number | null;
  officerList: Record<number, CityOfficerInfo | null>;
}

export interface CityOfficerInfo {
  officerLevel: number;
  name: string;
  npc: number;
}

export interface RecentRecordInfo {
  flushGeneral: boolean;
  flushGlobal: boolean;
  flushHistory: boolean;
  general: RecordEntry[];
  global: RecordEntry[];
  history: RecordEntry[];
}

export interface RecordEntry {
  id: number;
  message: string;
  date: string;
}

// Command table
export interface CommandTableEntry {
  actionCode: string;
  name: string;
  category: string;
  enabled: boolean;
  reason?: string;
  durationSeconds: number;
  commandPointCost: number;
}

// Contact
export interface ContactInfo {
  generalId: number;
  name: string;
  nationId: number;
  nationName: string;
  picture: string;
}

// Inheritance
export interface InheritanceInfo {
  points: number;
  buffs: Record<string, number>;
  log: InheritanceLogEntry[];
}

export interface InheritanceLogEntry {
  action: string;
  amount: number;
  date: string;
}

export interface InheritanceActionResult {
  remainingPoints?: number;
  newLevel?: number;
  error?: string;
}

// Tournament
export interface TournamentInfo {
  state: number;
  bracket: TournamentBracketMatch[];
  participants: number[];
}

export interface TournamentBracketMatch {
  round: number;
  match: number;
  p1: number;
  p2: number;
  winner?: number;
}

// Betting
export interface BettingInfo {
  bets: BetEntry[];
  odds: Record<string, number>;
}

export interface BetEntry {
  generalId: number;
  targetId: number;
  amount: number;
}

// Battle Simulator
export interface BattleSimUnit {
  name?: string;
  leadership?: number;
  strength?: number;
  intel?: number;
  crew?: number;
  crewType?: number;
  train?: number;
  atmos?: number;
  weaponCode?: string;
  bookCode?: string;
  horseCode?: string;
  specialCode?: string;
}

export interface BattleSimCity {
  def?: number;
  wall?: number;
  level?: number;
}

export interface BattleSimResult {
  winner: string;
  attackerRemaining: number;
  defenderRemaining: number;
  rounds: number;
  logs: string[];
}

// Nation Policy
export interface NationPolicyInfo {
  rate: number;
  bill: number;
  secretLimit: number;
  strategicCmdLimit: number;
  notice: string;
  scoutMsg: string;
}

// Officer
export interface OfficerInfo {
  id: number;
  name: string;
  picture: string;
  officerLevel: number;
  cityId: number;
}

// Troop (extended)
export interface TroopMemberInfo {
  id: number;
  name: string;
  picture: string;
}

export interface TroopWithMembers {
  troop: Troop;
  members: TroopMemberInfo[];
}

// Nation Statistic
export interface NationStatistic {
  nationId: number;
  name: string;
  color: string;
  level: number;
  gold: number;
  rice: number;
  tech: number;
  power: number;
  genCount: number;
  cityCount: number;
  totalCrew: number;
  totalPop: number;
}

// Admin
export interface AdminDashboard {
  worldCount: number;
  currentWorld: AdminWorldInfo | null;
}

export interface AdminWorldInfo {
  id: number;
  year: number;
  month: number;
  scenarioCode: string;
  realtimeMode: boolean;
  config: Record<string, unknown>;
}

export interface AdminUser {
  id: number;
  loginId: string;
  displayName: string;
  role: string;
  createdAt: string;
  lastLoginAt: string | null;
}
