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
  politics?: number; // 정치/매력 (RTK14 divergence)
  charm?: number;
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

// ── 도시정보 b_currentCity.php (GET /api/city/{id}) ───────────────────────────
// CityDetailController.CityDetailResponse 정합. 헤더/게이지 표면 + 장수 상세표 + 관직자행 + 군사집계행
// + 도시선택 셀렉터 + 갱신시각 + 도시명행 + 장수명 CSV. 첩보(fog) 마스킹: visible=false면 내정/방어 수치
// 전부 null(서버 마스킹). showDetailedInfo=false면 장수표/장수명 미노출("알 수 없음"). 수치 날조 없음.
export interface CityOfficerCell {
  name: string; // 부재 시 "-"(PHP 기본값)
  npc: number;
}

export interface CityMilitaryStat {
  enemyCrew: number;
  enemyArmedCnt: number;
  enemyCnt: number;
  crewTotal: number;
  armedGenTotal: number;
  genTotal: number;
  crew90: number;
  gen90: number;
  crew60: number;
  gen60: number;
  crewDef: number;
  genDef: number;
}

export interface CityGeneralRow {
  no: number;
  ourGeneral: boolean;
  iconPath: string; // picture 경로(FE가 CDN base 결합)
  npc: number;
  isNPC: boolean;
  wounded: number; // injury — 통무지에 formatWounded 적용
  name: string;
  leadership: number;
  strength: number;
  intel: number;
  politics?: number;
  charm?: number;
  officerLevel: number;
  officerLevelText: string;
  leadershipBonus: number;
  crewType: number;
  crewTypeName: string; // 비아국이면 ""(FE "?")
  crew: number; // 비가시 타국이면 -1(FE "?")
  train: number; // 비아국이면 -1
  atmos: number; // 비아국이면 -1
  nation: number;
  nationName: string;
}

export interface CityGeneralNameCell {
  name: string;
  npc: number;
}

export interface CitySelectorOption {
  cityId: number;
  cityName: string;
  relation: number; // 0 공백지 / 1 본국 / 2 타국
  selected: boolean;
}

export interface CityDetailResponse {
  id: number;
  name: string;
  level: number;
  levelName: string; // 치소 등급 한글명 (수/진/관/이/소/중/대/특)
  region: number;
  regionName: string; // 지역 한글명 (하북/중원/…/동이)
  nationId: number;
  visible: boolean; // 내정 가시 여부(소유/첩보/주둔). false면 아래 수치 null.
  population: number | null;
  populationMax: number | null;
  agriculture: number | null;
  agricultureMax: number | null;
  commerce: number | null;
  commerceMax: number | null;
  security: number | null;
  securityMax: number | null;
  defense: number | null;
  defenseMax: number | null;
  wall: number | null;
  wallMax: number | null;
  trust: number | null;
  trade: number | null;
  supplyState: number;
  frontState: number;
  officers: number | null;
  // ── b_currentCity.php 패러티 확장 ──────────────────────────────────────────
  showDetailedInfo: boolean; // 장수표/장수명 노출 게이트(visible과 별개)
  lastExecute: string | null; // "MM-DD HH:MM:SS" — config["turntime"] 미배선 시 null
  cityName: string;
  officerGovernor: CityOfficerCell; // 태수(officer_level 4)
  officerStrategist: CityOfficerCell; // 군사(officer_level 3)
  officerSecretary: CityOfficerCell; // 종사(officer_level 2)
  military: CityMilitaryStat;
  generals: CityGeneralRow[];
  generalNames: CityGeneralNameCell[];
  citySelector: CitySelectorOption[];
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
  politics?: number;
  charm?: number;
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
  politics?: number;
  charm?: number;
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

// GET /api/rankings/npcs → 빙의일람(a_npcList.php). WHERE npc_state=1, no rank field.
// 12컬럼: 희생장수(name,npc색) | 악령이름(ownerName, BLOCKED→null) | Lv(explevel) | 국가 | 성격(personalText)
//        | 특기(specialDomesticName/specialWarName) | 종능(total) | 통무지 | 명성(experience raw) | 계급(devotion raw)
export interface NpcGeneral {
  generalId: number;
  name: string;
  npc: number; // getNPCColor용(formatName 동치)
  nation: string;
  nationColor: string;
  officerLevel: number;
  ownerName: string | null; // 악령 이름 — BLOCKED(owner_name 컬럼 부재) → null
  explevel: number; // Lv
  personalText: string; // 성격 한글명
  specialDomesticName: string; // 내정 특기명
  specialWarName: string; // 전투 특기명
  leadership: number;
  strength: number;
  intel: number;
  politics?: number;
  charm?: number;
  total: number; // 종능 = 통+무+지
  experience: number; // 명성 컬럼 raw 값
  devotion: number; // 계급 컬럼 raw 값(= general.dedication)
  crew: number; // non-PHP 잉여(미렌더)
  cityName: string; // non-PHP 잉여(미렌더)
}

// GET /api/rankings/kingdom-roster → 세력일람(a_kingdomList.php) ROSTER. leaderboard와 별개.
export interface KingdomRoster {
  nations: KingdomRosterNation[]; // 국력 DESC
  neutral: KingdomRosterNeutral;
}
export interface KingdomRosterNation {
  nationId: number;
  name: string;
  color: string;
  typeCode: string; // 성향 — BLOCKED(한글명 헬퍼 미이식) → raw code
  level: number;
  levelText: string; // 작위(방랑군..황제; lv8/9 divergence)
  power: number; // 국력 = nation.power
  genNum: number;
  cityCount: number;
  chiefs: KingdomRosterChief[]; // officer_level 12→5 고정 8칸
  ambassadors: string[]; // 외교권자 — BLOCKED(permission 컬럼 부재) → []
  auditorCount: number; // 조언자 수 — BLOCKED → 0
  cities: KingdomRosterCity[]; // 속령 일람
  capitalCityId: number | null; // 수도(cyan 강조)
  generals: KingdomRosterGeneral[]; // 장수 일람(dedication DESC)
}
export interface KingdomRosterChief {
  officerLevelText: string;
  name: string; // 공석이면 "-"
  npc: number;
}
export interface KingdomRosterCity {
  cityId: number;
  name: string;
}
export interface KingdomRosterGeneral {
  name: string;
  npc: number;
}
export interface KingdomRosterNeutral {
  genNum: number;
  cityCount: number;
  cities: KingdomRosterCity[];
  generals: KingdomRosterGeneral[];
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
  politics?: number;
  charm?: number;
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
  politics?: number;
  charm?: number;
  explevel: number;        // 명성 레벨 버킷(getExpLevel) — 명성 표시·정렬 키
  honorText: string;       // 명성 칭호(getHonor)
  dedlevel: number;        // 계급 레벨 버킷(getDedLevel) — 계급 정렬 키
  dedLevelText: string;    // 계급 한글명(getDedLevelText)
  bill: number;            // 봉록(getBillByLevel)
  crew: number;
  cityName: string;
  // ── a_genList(장수일람) 15컬럼 보강(C3①) ─────────────────────────────────────
  picture: string | null;       // 얼굴(초상 파일명) — FE가 CDN base 합성
  imageServer: number;          // 초상 이미지 서버 번호
  age: number;                  // 연령("{age}세")
  personalText: string;         // 성격명(personalityNameOf)
  specialDomesticText: string;  // 내정 특기명(None→"-")
  specialWarText: string;       // 전투 특기명(None→"-")
  injury: number;               // 부상률(0~100) — >0이면 통/무/지 감산·적색
  lbonus: number;               // 통솔보너스(calcLeadershipBonus) — >0이면 통솔에 "+{lbonus}"(cyan)
  killturn: number | null;      // 삭턴(meta.killturn) — 미기재 시 null
  // 벌점(refresh_score_total)은 §2 BLOCKED(general_access_log 부재) — 필드 자체 미노출.
}

// GET /api/my-generals 의 실제 응답(백엔드 MyGeneralsResponse). b_myGenInfo(세력장수, fid 25) 쌍.
// a_genList(전체)와 컬럼 set이 유사하되 자금·군량·봉록·사관(belong)이 추가다.
export interface MyGeneralSummary {
  generalId: number;
  name: string;
  cityId: number;
  officerLevel: number;
  leadership: number;
  strength: number;
  intel: number;
  politics?: number;
  charm?: number;
  crew: number;
  npcState: number;
  mine: boolean;               // 호출자 본인 장수 여부
  // ── b_myGenInfo 15컬럼 보강(C3①) ────────────────────────────────────────────
  picture: string | null;       // 얼굴
  imageServer: number;
  officerLevelText: string;     // 관직 한글명
  dedLevelText: string;         // 계급 한글명
  honorText: string;            // 명성 칭호
  bill: number;                 // 봉록(getBill)
  gold: number;                 // 자금
  rice: number;                 // 군량
  personalText: string;         // 성격명
  specialDomesticText: string;  // 내정 특기명(None→"-")
  specialWarText: string;       // 전투 특기명(None→"-")
  belong: number;               // 사관(belong)
  injury: number;               // 부상률
  lbonus: number;               // 통솔보너스
  // 벌점(refresh_score_total)은 §2 BLOCKED(general_access_log 부재) — 필드 자체 미노출.
}

export interface MyGeneralsResponse {
  result: boolean;
  nationId: number;
  generals: MyGeneralSummary[];
}

// ── 세력정보 (GET /api/my-nation-detail · b_myKingdomInfo, fid 22) ─────────────
// 19필드(8열) 단일표. 계약 버그 수정 — 더 이상 {nation,generals,cities}가 아니다.
// 세율/지급률은 §2 BLOCKED(meta UNVERIFIED) → null 가능. income 6종/국가열전은 §2 BLOCKED로 미노출 → FE "-".
export interface MyNationCityRef {
  cityId: number;
  name: string;
  isCapital: boolean; // 수도 → cyan 강조
}

export interface MyNationDetailResponse {
  result: boolean;
  hasNation: boolean;
  nationId: number;
  name: string;
  color: string;
  population: number;     // 총주민 현재(SUM city.pop)
  populationMax: number;  // 총주민 최대(SUM city.pop_max)
  crew: number;           // 총병사 현재(SUM general.crew, npc!=5)
  crewMax: number;        // 총병사 최대(SUM general.leadership*100, npc!=5)
  power: number;          // 국력(nation.power)
  gold: number;           // 국고
  rice: number;           // 병량
  cityCount: number;      // 속령수
  generalCount: number;   // 장수수(gennum)
  tech: number;           // 기술력(floor)
  levelText: string;      // 작위 한글명(getNationLevel)
  level: number;          // raw level
  cities: MyNationCityRef[]; // 속령일람(수도 cyan)
  taxRate: number | null; // 세율 % — §2 BLOCKED(meta UNVERIFIED) → null
  bill: number | null;    // 지급률 % — §2 BLOCKED(meta UNVERIFIED) → null
  // income 6종(세금/단기/세곡/둔전/수입금·미/지출/예산/금미차) · 국가열전 = §2 BLOCKED → 미노출, FE "-".
}

// ── 세력도시 (GET /api/my-cities · b_myCityInfo, fid 22) ──────────────────────
// 평면 9컬럼 → 도시당 카드(5행). 시세 null→"- ", 민심 소수1자리. 수입 3종은 §2 BLOCKED로 미노출 → FE "-".
export interface MyCityGeneralName {
  name: string;
  npc: number; // getNPCColor 입력
}

export interface MyCitySummary {
  cityId: number;
  name: string;
  level: number;
  levelText: string;   // 등급 한글명(수/진/관/이/소/중/대/특)
  region: number;
  regionText: string;  // 지역 한글명(하북/중원/…/동이)
  isCapital: boolean;  // 수도 → 도시명 cyan
  population: number;
  populationMax: number;
  agriculture: number;
  agricultureMax: number;
  commerce: number;
  commerceMax: number;
  security: number;
  securityMax: number;
  defense: number;
  defenseMax: number;
  wall: number;
  wallMax: number;
  trust: number;       // 민심(소수1자리)
  trade: number | null; // 시세(null → "- ")
  governorName: string | null;   // 태수(공석 null → "-")
  governorNpc: number;
  strategistName: string | null; // 군사
  strategistNpc: number;
  secretaryName: string | null;  // 종사
  secretaryNpc: number;
  generals: MyCityGeneralName[];  // 도시 소재 장수(없으면 [] → "-")
  // 자금/군량/둔전 수입 3종 = §2 BLOCKED(income 파이프라인 미조립) → 미노출, FE "-".
}

export interface MyCitiesResponse {
  result: boolean;
  nationId: number;
  cities: MyCitySummary[];
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
//
// 와이어 키는 legacy `ts/diplomacy.ts`(LetterFullTarget/LetterNationTarget/LetterItem) +
// game-api `DiplomacyController.letters`와 1:1로 맞춘다(C1-α read-DTO shape 정합). 당사자(src/dest)는
// aux 스냅샷 그대로 = `{nationID, nationName, nationColor, generalName?, generalIcon?}`이며
// nationID만 컬럼값으로 덮인다. 미서명 수신측은 generalName/generalIcon이 부재(null/undefined).
export interface DiplomacyLetterParty {
  nationID: number;            // legacy `nationID` (대문자 D — aux/MessageTarget 키)
  nationName: string;          // legacy `nationName`
  nationColor: string;         // legacy `nationColor`
  generalName?: string | null; // legacy `generalName` — 서명 장수명(미서명 수신측이면 부재)
  generalIcon?: string | null; // legacy `generalIcon` — 서명 장수 초상 URL(미서명이면 부재)
}

export interface DiplomacyLetter {
  no: number;
  src: DiplomacyLetterParty;
  dest: DiplomacyLetterParty;
  prev_no: number | null;  // legacy `prev_no` (와이어 키 — game-api @JsonProperty("prev_no"))
  state: string;           // proposed/activated/cancelled/replaced (rendered verbatim)
  stateText: string;       // game-api 동봉 한글 라벨(제안됨/승인됨/거부됨/대체됨)
  state_opt: string | null; // legacy `state_opt` (와이어 키 — try_destroy_src/try_destroy_dest/null)
  brief: string;           // legacy `text_brief`
  detail: string;          // legacy `text_detail` (may be '(권한이 부족합니다)')
  date: string;
}

// `nations` 맵 1 값 — legacy NationStaticItem 부분집합(game-api DiplomacyNationInfo). 수신국 select
// 표시(`Lv.{level}`)에 사용. legacy 응답은 `Record<number, NationStaticItem>`(맵, 배열 아님).
export interface DiplomacyLetterNation {
  id: number;
  name: string;
  color: string;
  level: number;
}

export interface DiplomacyLettersResponse {
  result: boolean;
  // legacy `Record<number, NationStaticItem>` — id→nation 맵(자국 포함). FE가 키 순회로 후보국 도출.
  nations: Record<string, DiplomacyLetterNation>;
  letters: DiplomacyLetter[];         // 오래된→최신(game-api date ASC); [] when none
  myNationID: number;                 // legacy `myNationID`
}

// ── page 2 · 중원정보 (GET /api/diplomacy/conflict) ───────────────────────────
// Mirrors Global/GetDiplomacy.php. Matrix symbols ★/▲/ㆍ/@ + colors rendered by
// page verbatim. `conflict` is per-city 분쟁 share (%); diplomacyList masks
// neutral states 3-7 → 2 for nations not involving the viewer.
//
// [P0-19] 와이어 키는 PHP-verbatim — BE(F4Dto.SimpleNationObj)가 `nation`/`myNationID`로
// 직렬화한다(GetDiplomacy.php:98-104 그대로, F4ReadControllersTest로 증명). 이전 FE 타입이
// `nationId`/`myNationId`로 발산해 페이지 전체가 silent 붕괴했었음 — 절대 다시 리네임 금지.
export interface ConflictNation {
  nation: number;          // 국가 id — PHP `nation` 컬럼명 verbatim
  name: string;
  color: string;
  type: string;            // 국가 성향 type_code (PHP `type`)
  level: number;
  capital: number;         // 수도 도시 id (PHP `capital`, 없으면 0)
  gennum: number;          // 장수 수 (PHP `gennum`) — P1-038 국가표 '장수' 컬럼 소비처
  cities: string[];        // 보유 도시명 (insertion order preserved)
  power: number;
}

// [cityId, { nationId: sharePct }] — share rounded to 1 dp (PhpRound half-away).
export type ConflictCity = [number, Record<number, number>];

export interface DiplomacyConflictResponse {
  result: boolean;
  nations: ConflictNation[];               // active nations (level>0), power DESC
  conflict: ConflictCity[];                // [] when no contested cities
  diplomacyList: Record<number, Record<number, number>>; // {me:{you:stateCode}}
  myNationID: number;                      // PHP `myNationID` verbatim (P0-19 — 대문자 ID)
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

// 외교관계 표(legacy PageNationStratFinan.vue:4-46) 1행 — getAllNationStaticInfo + cityCnt + diplomacy.
export interface NationFinanceDiplomacyState {
  state: number;           // 0 교전 / 1 선포중 / 2 통상 / 7 불가침(자국). 그 외는 통상 폴백.
  term: number | null;     // 잔여 개월(0 또는 자국이면 '-').
}

export interface NationFinanceNationItem {
  nation: number;          // 국가 id(PHP `nation` 컬럼명)
  name: string;
  color: string;
  type: string;
  level: number;
  capital: number;
  gennum: number;          // 장수 수
  power: number;           // 국력
  cityCnt: number;         // 속령 수
  diplomacy: NationFinanceDiplomacyState;
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
  // 외교관계 표(legacy 내무부 상단). game-api 가 미배출(구 이미지)이면 optional → 미렌더.
  nationsList?: NationFinanceNationItem[];
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
  politics?: number;
  charm?: number;
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
// BE TroopsResponse(F4Dto.kt)의 실제 와이어 형태에 1:1 정렬한 타입(Direction A — 날조 필드 없음).
// 멤버십/뮤테이션 게이팅은 응답의 myGeneralId(레거시 myGeneralID)와 permission(레거시 myPermission)에서
// 파생한다(legacy hwe/ts/PageTroop.vue). 멤버 소재 도시는 한글 cityName으로 표시(숫자 id 아님, bug #11).
export interface TroopMember {
  generalId: number;
  name: string;
  officerLevel: number;
  crew: number;
  cityName: string;       // 멤버 소재 도시 한글명(빈 문자열이면 미배치)
  npc: number;            // getNPCColor 색상 티어(0 유저/1 빙의/2+ 순수 NPC)
}

// BE TroopRow와 1:1. (기존 소비처 호환을 위해 이름은 TroopInfo 유지 — 필드만 실제 와이어로 정렬.)
export interface TroopInfo {
  troopLeader: number;    // = 부대장 generalId (부대 PK)
  name: string;           // 부대명
  nation: number;
  leaderName: string;
  leaderCityName: string; // 카드 헤더 '【 <city> 】'
  leaderNpc: number;      // 부대장 이름 색상 티어
  turnTime: string;       // 'YYYY-MM-DD HH:MM:SS' (레거시 '【턴】' = turnTime.slice(14,19)), 없으면 ''
  reservedCommandBrief: string[];  // 예약명령 브리핑(현재 BE 미배선 → 빈 목록)
  members: TroopMember[];
  memberCount: number;    // (N명) 헤더 카운트
}

export interface TroopListResponse {
  result: boolean;
  troops: TroopInfo[];     // [] when no troops formed
  myGeneralId: number;     // 호출자 빙의 장수 id(레거시 myGeneralID). 미인증/무빙의=0
  permission: number;      // 레거시 myPermission(0 일반/1 관직자/2 수뇌)
}

// ── page 16 · 연감 (GET /api/history?yearMonth=) ──────────────────────────────
// HistoryController.HistoryResponse 정합(PageHistory.vue + Global/GetHistory.php). 단일 서버(F4, OQ-8).
// yearMonth = Util::joinYearMonth = year*12 + (month-1). 셀렉터 범위 [firstYearMonth, lastYearMonth].
// 행이 없으면 record=null + 범위 0. record로 MapViewer(map)/SimpleNationList(nations)/중원정세·장수동향 렌더.
// BLOCKED: yearbook_history에 global_history/global_action 컬럼 부재 → 항상 [](서버 BLOCKED, 날조 없음).

// SimpleNationList(legacy SimpleNationObj) 행 — record.nations jsonb 스냅샷의 원소. 키는 jsonb 원형이라
// 일부 필드가 없을 수 있어 모두 optional로 둔다(렌더 시 ?? 폴백, 날조 없음).
export interface SimpleNationObj {
  nation: number;
  name: string;
  color: string;
  power: number;
  gennum: number;       // 장수 수
  cities?: number[];    // 속령(툴팁용)
}

export interface HistoryRecord {
  serverId: string;        // legacy `server_id`
  year: number;
  month: number;
  globalHistory: string[]; // legacy `global_history` — BLOCKED(컬럼 부재) → 항상 []
  globalAction: string[];  // legacy `global_action`  — BLOCKED(컬럼 부재) → 항상 []
  nations: SimpleNationObj[] | Record<string, SimpleNationObj> | null; // jsonb 원형(배열/맵)
  map: unknown;            // legacy `map` snapshot (MapViewer 입력)
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

// ── 서신함 (GET /api/mailbox/{mailbox} → MailboxMessage[] · GET /api/messages/{id}) ──
// [P0-33] game-api MessageDto.kt(MessageResponse/MsgTarget)의 실제 와이어 1:1 미러.
// legacy 계약: hwe/ts/defs/API/Message.ts MsgItem/MsgTarget (src/dest 타깃 블록 + text + time).
// 이전 mailbox 페이지의 로컬 MailMessage 인터페이스(srcName/date/read 등)는 실DTO와 불일치 —
// 발신자/시각 공란 + 위조 '미읽음' 배지의 근원이었다. legacy엔 read 플래그가 없고
// latestRead 커서(sequence)만 존재 — read/unreadCount류 필드를 여기에 추가(날조)하지 말 것.
// TODO(P0-33, W1-L): mailbox 페이지가 이 타입을 소비하도록 교체(srcTarget?.name / time / text 매핑).

/** legacy MsgType verbatim — message.type 컬럼 값(type.value, 소문자). */
export type MailMsgType = 'private' | 'public' | 'national' | 'diplomacy';

/**
 * 메시지 발/수신 대상 — PHP `MessageTarget::toArray()` `{id, name, nation_id, nation, color, icon}`.
 * BE(MessageDto.MsgTarget)가 camelCase(nationId)로 직렬화한다(프록시 pass-through).
 */
export interface MailMsgTarget {
  id: number;             // 장수 id (시스템 타깃은 0)
  name: string;           // 장수 이름 (시스템 타깃은 "")
  nationId: number;       // 소속 국가 id (재야/시스템 0) — legacy `nation_id`
  nation: string;         // 소속 국가 이름 (재야 '재야')
  color: string;          // 국가색 hex
  icon: string | null;    // 아이콘 경로
}

/** GET /api/mailbox/{mailbox} 응답 1행 — BE MessageResponse 그대로(bare 배열로 내려온다). */
export interface MailboxMessage {
  id: number | null;
  mailbox: number;
  type: MailMsgType;
  src: number;            // 발신 장수 id(라우팅 키 int 컬럼)
  dest: number;           // 수신 장수 id
  time: string;           // ISO instant — 표시 시각은 여기서(legacy MsgItem.time)
  validUntil: string;     // ISO instant — 유효기한
  message: string;        // 원본 body jsonb 문자열(호환 보존)
  text: string | null;    // body `text` — 표시 본문
  srcTarget: MailMsgTarget | null;  // 발신 타깃 블록 — 발신자명은 srcTarget.name
  destTarget: MailMsgTarget | null; // 수신 타깃 블록(공개 메시지면 null)
  option: Record<string, unknown> | null; // body `option`(action/deletable/receiverMessageID 등)
}
