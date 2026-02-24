import api from "./api";
import type {
  WorldState,
  Nation,
  City,
  General,
  Troop,
  Diplomacy,
  Message,
  GeneralTurn,
  NationTurn,
  CommandResult,
  CommandTableEntry,
  Scenario,
  MapData,
  FrontInfoResponse,
  ContactInfo,
  InheritanceInfo,
  InheritanceActionResult,
  TournamentInfo,
  BettingInfo,
  BettingEventSummary,
  BattleSimUnit,
  BattleSimCity,
  BattleSimResult,
  BattleSimRepeatResult,
  NationPolicyInfo,
  OfficerInfo,
  TroopWithMembers,
  NationStatistic,
  AdminDashboard,
  AdminUser,
  AdminGeneral,
  RealtimeStatus,
  AuctionBidResponse,
  AccountSettings,
  MailboxType,
  NpcTokenResponse,
  SelectNpcResult,
  BestGeneral,
  YearbookSummary,
  BoardComment,
  VoteComment,
  PublicCachedMapResponse,
} from "@/types";

// World API
export const worldApi = {
  list: () => api.get<WorldState[]>("/worlds"),
  get: (id: number) => api.get<WorldState>(`/worlds/${id}`),
  create: (payload: {
    scenarioCode: string;
    name?: string;
    tickSeconds?: number;
    commitSha?: string;
    gameVersion?: string;
  }) => api.post<WorldState>("/worlds", payload),
  delete: (id: number) => api.delete<void>(`/worlds/${id}`),
  reset: (id: number, scenarioCode?: string) =>
    api.post<WorldState>(
      `/worlds/${id}/reset`,
      scenarioCode ? { scenarioCode } : {},
    ),
  activate: (
    id: number,
    payload?: {
      commitSha?: string;
      gameVersion?: string;
      jarPath?: string;
      port?: number;
      javaCommand?: string;
    },
  ) => api.post<void>(`/worlds/${id}/activate`, payload ?? {}),
  deactivate: (id: number) => api.post<void>(`/worlds/${id}/deactivate`),
};

// Nation API
export const nationApi = {
  listByWorld: (worldId: number) =>
    api.get<Nation[]>(`/worlds/${worldId}/nations`),
  get: (id: number) => api.get<Nation>(`/nations/${id}`),
};

// City API
export const cityApi = {
  listByWorld: (worldId: number) =>
    api.get<City[]>(`/worlds/${worldId}/cities`),
  get: (id: number) => api.get<City>(`/cities/${id}`),
  listByNation: (nationId: number) =>
    api.get<City[]>(`/nations/${nationId}/cities`),
};

// General API
export const generalApi = {
  listByWorld: (worldId: number) =>
    api.get<General[]>(`/worlds/${worldId}/generals`),
  get: (id: number) => api.get<General>(`/generals/${id}`),
  getMine: (worldId: number) =>
    api.get<General>(`/worlds/${worldId}/generals/me`),
  listByNation: (nationId: number) =>
    api.get<General[]>(`/nations/${nationId}/generals`),
  listByCity: (cityId: number) =>
    api.get<General[]>(`/cities/${cityId}/generals`),
  listAvailableNpcs: (worldId: number) =>
    api.get<General[]>(`/worlds/${worldId}/available-npcs`),
  selectNpc: (worldId: number, generalId: number) =>
    api.post<General>(`/worlds/${worldId}/select-npc`, { generalId }),
  listPool: (worldId: number) => api.get<General[]>(`/worlds/${worldId}/pool`),
  selectFromPool: (worldId: number, generalId: number) =>
    api.post<General>(`/worlds/${worldId}/select-pool`, { generalId }),
  buildPoolGeneral: (
    worldId: number,
    payload: { name: string; leadership: number; strength: number; intel: number; politics: number; charm: number },
  ) => api.post<General>(`/worlds/${worldId}/pool`, payload),
  updatePoolGeneral: (
    worldId: number,
    generalId: number,
    stats: { leadership: number; strength: number; intel: number; politics: number; charm: number },
  ) => api.put<General>(`/worlds/${worldId}/pool/${generalId}`, stats),
};

export const npcTokenApi = {
  generate: (worldId: number) =>
    api.post<NpcTokenResponse>(`/worlds/${worldId}/npc-token`),
  refresh: (worldId: number, nonce: string, keepIds: number[]) =>
    api.post<NpcTokenResponse>(`/worlds/${worldId}/npc-token/refresh`, {
      nonce,
      keepIds,
    }),
  select: (worldId: number, nonce: string, generalId: number) =>
    api.post<SelectNpcResult>(`/worlds/${worldId}/npc-select`, {
      nonce,
      generalId,
    }),
};

// Command API
export const commandApi = {
  getReservedCommands: (generalId: number) =>
    api.get<GeneralTurn[]>(`/generals/${generalId}/turns`),
  reserveCommand: (
    generalId: number,
    payload: { turn: number; command: string; arg?: Record<string, unknown> },
  ) =>
    api.post<void>(`/generals/${generalId}/turns`, {
      turns: [
        {
          turnIdx: payload.turn,
          actionCode: payload.command,
          arg: payload.arg,
        },
      ],
    }),
  deleteReservedCommand: (generalId: number, turn: number) =>
    api.post<void>(`/generals/${generalId}/turns`, {
      turns: [{ turnIdx: turn, actionCode: "휴식" }],
    }),
  pushReservedCommand: (generalId: number, turn: number) =>
    api.post<GeneralTurn[]>(`/generals/${generalId}/turns/push`, {
      amount: -Math.max(0, turn),
    }),
  getReserved: (generalId: number) =>
    api.get<GeneralTurn[]>(`/generals/${generalId}/turns`),
  reserve: (
    generalId: number,
    turns: {
      turnIdx: number;
      actionCode: string;
      arg?: Record<string, unknown>;
    }[],
  ) => api.post<void>(`/generals/${generalId}/turns`, { turns }),
  execute: (
    generalId: number,
    actionCode: string,
    arg?: Record<string, unknown>,
  ) =>
    api.post<CommandResult>(`/generals/${generalId}/execute`, {
      actionCode,
      arg,
    }),
  executeNation: (
    generalId: number,
    actionCode: string,
    arg?: Record<string, unknown>,
  ) =>
    api.post<CommandResult>(`/generals/${generalId}/execute-nation`, {
      actionCode,
      arg,
    }),
  getNationReserved: (nationId: number, officerLevel: number) =>
    api.get<NationTurn[]>(`/nations/${nationId}/turns`, {
      params: { officerLevel },
    }),
  reserveNation: (
    nationId: number,
    generalId: number,
    turns: {
      turnIdx: number;
      actionCode: string;
      arg?: Record<string, unknown>;
    }[],
  ) =>
    api.post<NationTurn[]>(
      `/nations/${nationId}/turns`,
      { turns },
      { params: { generalId } },
    ),
  getCommandTable: (generalId: number) =>
    api.get<Record<string, CommandTableEntry[]>>(
      `/generals/${generalId}/command-table`,
    ),
  getNationCommandTable: (generalId: number) =>
    api.get<Record<string, CommandTableEntry[]>>(
      `/generals/${generalId}/nation-command-table`,
    ),
  repeatTurns: (generalId: number, count: number) =>
    api.post<GeneralTurn[]>(`/generals/${generalId}/turns/repeat`, { count }),
  pushTurns: (generalId: number, amount: number) =>
    api.post<GeneralTurn[]>(`/generals/${generalId}/turns/push`, { amount }),
};

export const realtimeApi = {
  execute: (
    generalId: number,
    actionCode: string,
    arg?: Record<string, unknown>,
  ) =>
    api.post<CommandResult>("/realtime/execute", {
      generalId,
      actionCode,
      arg,
    }),
  getStatus: (generalId: number) =>
    api.get<RealtimeStatus>(`/realtime/status/${generalId}`),
};

// Diplomacy API
export const diplomacyApi = {
  listByWorld: (worldId: number) =>
    api.get<Diplomacy[]>(`/worlds/${worldId}/diplomacy`),
  listByNation: (worldId: number, nationId: number) =>
    api.get<Diplomacy[]>(`/worlds/${worldId}/diplomacy/nation/${nationId}`),
};

// Message API
export const messageApi = {
  getByType: (
    type: "public" | "national" | "private" | "diplomacy",
    params: {
      worldId?: number;
      nationId?: number;
      generalId?: number;
      officerLevel?: number;
      beforeId?: number;
      limit?: number;
    },
  ) => api.get<Message[]>("/messages", { params: { type, ...params } }),
  getMine: (generalId: number) =>
    api.get<Message[]>(`/messages`, { params: { generalId } }),
  send: (
    worldId: number,
    srcId: number,
    destId: number | null,
    content: string,
    options?: {
      mailboxCode?: string;
      mailboxType?: MailboxType;
      messageType?: string;
      officerLevel?: number;
    },
  ) =>
    api.post<Message>("/messages", {
      worldId,
      mailboxCode: options?.mailboxCode ?? "personal",
      mailboxType: options?.mailboxType ?? "PRIVATE",
      messageType: options?.messageType ?? "personal",
      srcId,
      destId,
      officerLevel: options?.officerLevel,
      payload: { content },
    }),
  getBoard: (worldId: number) =>
    api.get<Message[]>("/messages/board", { params: { worldId } }),
  postBoard: (worldId: number, srcId: number, content: string, title?: string) =>
    api.post<Message>("/messages", {
      worldId,
      mailboxCode: "board",
      mailboxType: "PUBLIC",
      messageType: "board",
      srcId,
      payload: { content, ...(title ? { title } : {}) },
    }),
  getSecretBoard: (worldId: number, nationId: number) =>
    api.get<Message[]>("/messages/secret-board", {
      params: { worldId, nationId },
    }),
  postSecretBoard: (
    worldId: number,
    srcId: number,
    nationId: number,
    content: string,
    title?: string,
  ) =>
    api.post<Message>("/messages", {
      worldId,
      mailboxCode: "secret",
      mailboxType: "NATIONAL",
      messageType: "secret",
      srcId,
      destId: nationId,
      payload: { content, ...(title ? { title } : {}) },
    }),
  getContacts: (worldId: number) =>
    api.get<ContactInfo[]>(`/worlds/${worldId}/contacts`),
  respondDiplomacy: (messageId: number, accept: boolean) =>
    api.post<void>(`/messages/${messageId}/diplomacy-respond`, { accept }),
  getRecent: (sequence: number) =>
    api.get<Message[]>("/messages/recent", { params: { sequence } }),
  delete: (id: number) => api.delete<void>(`/messages/${id}`),
  markAsRead: (id: number) => api.patch<void>(`/messages/${id}/read`),
};

// FrontInfo API
export const frontApi = {
  getInfo: (worldId: number, lastRecordId?: number, lastHistoryId?: number) => {
    const params: Record<string, number> = {};
    if (lastRecordId != null) params.lastRecordId = lastRecordId;
    if (lastHistoryId != null) params.lastHistoryId = lastHistoryId;
    return api.get<FrontInfoResponse>(`/worlds/${worldId}/front-info`, {
      params,
    });
  },
};

// History API
export const historyApi = {
  getWorldHistory: (worldId: number) =>
    api.get<Message[]>(`/worlds/${worldId}/history`),
  getWorldHistoryByYearMonth: (worldId: number, year: number, month: number) =>
    api.get<Message[]>(`/worlds/${worldId}/history`, {
      params: { year, month },
    }),
  getYearbook: (worldId: number, year: number) =>
    api.get<YearbookSummary>(`/worlds/${worldId}/history/yearbook`, {
      params: { year },
    }),
  getWorldRecords: (worldId: number) =>
    api.get<Message[]>(`/worlds/${worldId}/records`),
  getGeneralRecords: (generalId: number) =>
    api.get<Message[]>(`/generals/${generalId}/records`),
};

// General Log API (legacy parity: battle center per-general logs)
export interface GeneralLogEntry {
  id: number;
  message: string;
  date: string;
}
export interface GeneralLogResult {
  result: boolean;
  reason?: string;
  logs: GeneralLogEntry[];
}
export const generalLogApi = {
  getOldLogs: (
    generalId: number,
    targetId: number,
    type: "generalHistory" | "generalAction" | "battleResult" | "battleDetail",
    to?: number,
  ) =>
    api.get<GeneralLogResult>(
      `/generals/${generalId}/logs/old`,
      { params: { targetId, type, ...(to ? { to } : {}) } },
    ),
};

// Simulator Export API (legacy parity: load general from server)
export interface SimulatorExportResult {
  result: boolean;
  reason?: string;
  data?: Record<string, unknown>;
}
export const simulatorExportApi = {
  exportGeneral: (generalId: number, targetId: number) =>
    api.get<SimulatorExportResult>(
      `/generals/${generalId}/simulator-export`,
      { params: { targetId } },
    ),
};

// Map Recent API (cached map with history)
export const mapRecentApi = {
  getMapRecent: (worldId: number) =>
    api.get<PublicCachedMapResponse>(`/worlds/${worldId}/map-recent`),
};

export const boardApi = {
  getComments: (postId: number) =>
    api.get<BoardComment[]>(`/boards/${postId}/comments`),
  createComment: (postId: number, authorGeneralId: number, content: string) =>
    api.post<BoardComment>(`/boards/${postId}/comments`, {
      authorGeneralId,
      content,
    }),
  deleteComment: (postId: number, commentId: number, generalId: number) =>
    api.delete<void>(`/boards/${postId}/comments/${commentId}`, {
      params: { generalId },
    }),
};

// Account API
export const accountApi = {
  changePassword: (currentPassword: string, newPassword: string) =>
    api.patch<void>("/account/password", { currentPassword, newPassword }),
  deleteAccount: () => api.delete<void>("/account"),
  updateSettings: (settings: AccountSettings) =>
    api.patch<void>("/account/settings", settings),
  toggleVacation: () => api.post<void>("/account/vacation"),
};

// Nation Management API
export const nationManagementApi = {
  getOfficers: (nationId: number) =>
    api.get<OfficerInfo[]>(`/nations/${nationId}/officers`),
  appointOfficer: (
    nationId: number,
    data: { generalId: number; officerLevel: number; officerCity?: number },
  ) => api.post<void>(`/nations/${nationId}/officers`, data),
  expel: (nationId: number, generalId: number) =>
    api.post<void>(`/nations/${nationId}/expel`, { generalId }),
};

// Nation Policy API
export const nationPolicyApi = {
  getPolicy: (nationId: number) =>
    api.get<NationPolicyInfo>(`/nations/${nationId}/policy`),
  updatePolicy: (nationId: number, data: Record<string, unknown>) =>
    api.patch<void>(`/nations/${nationId}/policy`, data),
  updateNotice: (nationId: number, notice: string) =>
    api.patch<void>(`/nations/${nationId}/notice`, { notice }),
  updateScoutMsg: (nationId: number, scoutMsg: string) =>
    api.patch<void>(`/nations/${nationId}/scout-msg`, { scoutMsg }),
};

// NPC Policy API (dynamic policy maps - intentionally loose)
export const npcPolicyApi = {
  getPolicy: (nationId: number) =>
    api.get<Record<string, unknown>>(`/nations/${nationId}/npc-policy`),
  updatePolicy: (nationId: number, policy: Record<string, unknown>) =>
    api.put<void>(`/nations/${nationId}/npc-policy`, policy),
  updatePriority: (nationId: number, priority: Record<string, unknown>) =>
    api.put<void>(`/nations/${nationId}/npc-priority`, priority),
};

// Troop API
export const troopApi = {
  listByNation: (nationId: number) =>
    api.get<TroopWithMembers[]>(`/nations/${nationId}/troops`),
  create: (data: {
    worldId: number;
    leaderGeneralId: number;
    nationId: number;
    name: string;
  }) => api.post<Troop>("/troops", data),
  join: (troopId: number, generalId: number) =>
    api.post<void>(`/troops/${troopId}/join`, { generalId }),
  exit: (troopId: number, generalId: number) =>
    api.post<void>(`/troops/${troopId}/exit`, { generalId }),
  kick: (troopId: number, generalId: number) =>
    api.post<void>(`/troops/${troopId}/kick`, { generalId }),
  rename: (troopId: number, name: string) =>
    api.patch<Troop>(`/troops/${troopId}`, { name }),
  disband: (troopId: number) => api.delete<void>(`/troops/${troopId}`),
};

// Diplomacy Letter API
export const diplomacyLetterApi = {
  list: (nationId: number) =>
    api.get<Message[]>(`/nations/${nationId}/diplomacy-letters`),
  send: (
    nationId: number,
    data: {
      worldId: number;
      destNationId: number;
      type: string;
      content?: string;
      diplomaticContent?: string;
    },
  ) => api.post<Message>(`/nations/${nationId}/diplomacy-letters`, data),
  respond: (letterId: number, accept: boolean) =>
    api.post<void>(`/diplomacy-letters/${letterId}/respond`, { accept }),
  execute: (letterId: number) =>
    api.post<void>(`/diplomacy-letters/${letterId}/execute`),
  rollback: (letterId: number) =>
    api.post<void>(`/diplomacy-letters/${letterId}/rollback`),
  destroy: (letterId: number) =>
    api.post<void>(`/diplomacy-letters/${letterId}/destroy`),
};

// Ranking API
export const rankingApi = {
  bestGenerals: (worldId: number, sortBy?: string, limit?: number) => {
    const params: Record<string, string | number> = {};
    if (sortBy) params.sortBy = sortBy;
    if (limit) params.limit = limit;
    return api.get<BestGeneral[]>(`/worlds/${worldId}/best-generals`, {
      params,
    });
  },
  hallOfFame: (worldId: number, params?: { season?: number; scenario?: string }) =>
    api.get<Message[]>(`/worlds/${worldId}/hall-of-fame`, { params }),
  hallOfFameOptions: (worldId: number) =>
    api.get<{ seasons: { id: number; label: string; scenarios: { code: string; label: string }[] }[] }>(`/worlds/${worldId}/hall-of-fame/options`),
  uniqueItemOwners: (worldId: number) =>
    api.get<{ slot: string; slotLabel: string; generalId: number; generalName: string; nationId: number; nationName: string; nationColor: string; itemName: string; itemGrade: string }[]>(`/worlds/${worldId}/unique-item-owners`),
};

// Traffic API
export const trafficApi = {
  getTraffic: (worldId: number) =>
    api.get<{
      recentTraffic: {
        year: number;
        month: number;
        refresh: number;
        online: number;
        date: string;
      }[];
      maxRefresh: number;
      maxOnline: number;
      topRefreshers: {
        name: string;
        refresh: number;
        refreshScoreTotal: number;
      }[];
      totalRefresh: number;
      totalRefreshScoreTotal: number;
    }>(`/worlds/${worldId}/traffic`),
};

// Scenario API
export const scenarioApi = {
  list: () => api.get<Scenario[]>("/scenarios"),
};

// Map API
export const mapApi = {
  get: (mapName: string) => api.get<MapData>(`/maps/${mapName}`),
};

// Inheritance API
export const inheritanceApi = {
  getInfo: (worldId: number) =>
    api.get<InheritanceInfo>(`/worlds/${worldId}/inheritance`),
  buy: (worldId: number, buffCode: string) =>
    api.post<InheritanceActionResult>(`/worlds/${worldId}/inheritance/buy`, {
      buffCode,
    }),
  setSpecial: (worldId: number, specialCode: string) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/special`,
      { specialCode },
    ),
  setCity: (worldId: number, cityId: number) =>
    api.post<InheritanceActionResult>(`/worlds/${worldId}/inheritance/city`, {
      cityId,
    }),
  resetTurn: (worldId: number) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/reset-turn`,
    ),
  buyRandomUnique: (worldId: number) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/random-unique`,
    ),
  resetSpecialWar: (worldId: number) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/reset-special-war`,
    ),
  resetStats: (
    worldId: number,
    stats: { leadership: number; strength: number; intel: number },
  ) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/reset-stats`,
      stats,
    ),
  checkOwner: (worldId: number, generalName: string) =>
    api.post<{ ownerName?: string; found: boolean }>(
      `/worlds/${worldId}/inheritance/check-owner`,
      { generalName },
    ),
  auctionUnique: (
    worldId: number,
    data: { uniqueCode: string; bidAmount: number },
  ) =>
    api.post<InheritanceActionResult>(
      `/worlds/${worldId}/inheritance/auction-unique`,
      data,
    ),
};

// Auction API
export const auctionApi = {
  list: (worldId: number) => api.get<Message[]>(`/worlds/${worldId}/auctions`),
  create: (
    worldId: number,
    data: {
      type: string;
      sellerId: number;
      item: string;
      amount: number;
      minPrice: number;
    },
  ) => api.post<Message>(`/worlds/${worldId}/auctions`, data),
  bid: (auctionId: number, bidderId: number, amount: number) =>
    api.post<AuctionBidResponse>(`/auctions/${auctionId}/bid`, {
      bidderId,
      amount,
    }),
};

// Item API
export const itemApi = {
  discard: (generalId: number, itemType: string) =>
    api.post<CommandResult>(`/generals/${generalId}/items/discard`, {
      itemType,
    }),
  equip: (generalId: number, itemCode: string, itemType: string) =>
    api.post<CommandResult>(`/generals/${generalId}/items/equip`, {
      itemCode,
      itemType,
    }),
  unequip: (generalId: number, itemType: string) =>
    api.post<CommandResult>(`/generals/${generalId}/items/unequip`, {
      itemType,
    }),
  use: (generalId: number, itemType: string, itemCode: string) =>
    api.post<CommandResult>(`/generals/${generalId}/items/use`, {
      itemType,
      itemCode,
    }),
  give: (generalId: number, targetGeneralId: number, itemType: string) =>
    api.post<CommandResult>(`/generals/${generalId}/items/give`, {
      targetGeneralId,
      itemType,
    }),
};

// Tournament API
export const tournamentApi = {
  getInfo: (worldId: number) =>
    api.get<TournamentInfo>(`/worlds/${worldId}/tournament`),
  register: (worldId: number, generalId: number) =>
    api.post<void>(`/worlds/${worldId}/tournament/register`, { generalId }),
  advancePhase: (worldId: number) =>
    api.post<void>(`/worlds/${worldId}/tournament/advance`),
  sendMessage: (worldId: number, message: string) =>
    api.post<void>(`/worlds/${worldId}/tournament/message`, { message }),
};

// Betting API
export const bettingApi = {
  getInfo: (worldId: number) =>
    api.get<BettingInfo>(`/worlds/${worldId}/betting`),
  getHistory: (worldId: number) =>
    api.get<BettingEventSummary[]>(`/worlds/${worldId}/betting/history`),
  getEvent: (worldId: number, yearMonth: string) =>
    api.get<BettingInfo>(`/worlds/${worldId}/betting/${yearMonth}`),
  placeBet: (
    worldId: number,
    generalId: number,
    targetId: number,
    amount: number,
  ) =>
    api.post<void>(`/worlds/${worldId}/betting`, {
      generalId,
      targetId,
      amount,
    }),
  toggleGate: (worldId: number, open: boolean) =>
    api.post<void>(`/worlds/${worldId}/betting/gate`, { open }),
};

// Vote API
export const voteApi = {
  list: (worldId: number) => api.get<Message[]>(`/worlds/${worldId}/votes`),
  create: (
    worldId: number,
    data: { title: string; options: string[]; creatorId: number },
  ) => api.post<Message>(`/worlds/${worldId}/votes`, data),
  cast: (voteId: number, voterId: number, optionIndex: number) =>
    api.post<void>(`/votes/${voteId}/cast`, { voterId, optionIndex }),
  close: (voteId: number) => api.post<void>(`/votes/${voteId}/close`),
  listComments: (voteId: number) =>
    api.get<VoteComment[]>(`/votes/${voteId}/comments`),
  createComment: (voteId: number, authorGeneralId: number, content: string) =>
    api.post<VoteComment>(`/votes/${voteId}/comments`, {
      authorGeneralId,
      content,
    }),
  deleteComment: (voteId: number, commentId: number, generalId: number) =>
    api.delete<void>(`/votes/${voteId}/comments/${commentId}`, {
      params: { generalId },
    }),
};

// Battle Simulator API
export const battleSimApi = {
  simulate: (
    attacker: BattleSimUnit,
    defender: BattleSimUnit,
    defenderCity: BattleSimCity,
    options?: { year?: number; month?: number; seed?: string; repeatCount?: number },
  ) =>
    api.post<BattleSimResult & { repeatSummary?: BattleSimRepeatResult }>(
      "/battle/simulate",
      {
        attacker,
        defender,
        defenderCity,
        ...options,
      },
    ),
};

// Game Version API (Admin)
export const gameVersionApi = {
  list: () =>
    api.get<
      {
        commitSha: string;
        gameVersion: string;
        jarPath: string;
        port: number;
        worldIds: number[];
        alive: boolean;
        pid: number;
        baseUrl: string;
        containerId: string | null;
        imageTag: string | null;
      }[]
    >("/admin/game-versions"),
  deploy: (data: {
    gameVersion: string;
    imageTag?: string;
    commitSha?: string;
  }) => api.post<unknown>("/admin/game-versions", data),
  stop: (version: string) =>
    api.delete<void>(`/admin/game-versions/${encodeURIComponent(version)}`),
};

// Admin API
export const adminApi = {
  getDashboard: () => api.get<AdminDashboard>("/admin/dashboard"),
  updateSettings: (settings: Record<string, unknown>) =>
    api.patch<void>("/admin/settings", settings),
  listGenerals: () => api.get<AdminGeneral[]>("/admin/generals"),
  generalAction: (id: number, type: string) =>
    api.post<void>(`/admin/generals/${id}/action`, { type }),
  getStatistics: () => api.get<NationStatistic[]>("/admin/statistics"),
  getGeneralLogs: (id: number) =>
    api.get<Message[]>(`/admin/generals/${id}/logs`),
  getDiplomacy: () => api.get<Diplomacy[]>("/admin/diplomacy"),
  timeControl: (data: Record<string, unknown>) =>
    api.post<void>("/admin/time-control", data),
  listUsers: () => api.get<AdminUser[]>("/admin/users"),
  userAction: (
    id: number,
    action: {
      type: "setAdmin" | "removeAdmin" | "delete" | "setGrade";
      grade?: number;
    },
  ) => api.post<void>(`/admin/users/${id}/action`, action),
  createWorld: (data: { scenarioCode: string; turnTerm?: number; notice?: string }) =>
    api.post<{ worldId: number }>("/admin/worlds", data),
  deleteWorld: (worldId: number) =>
    api.delete<void>(`/admin/worlds/${worldId}`),
  listWorlds: () =>
    api.get<{ id: number; scenarioCode: string; year: number; month: number; locked: boolean }[]>("/admin/worlds"),
  bulkGeneralAction: (ids: number[], type: string) =>
    api.post<void>("/admin/generals/bulk-action", { ids, type }),
};
