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

export interface MyPageData {
  general: General;
  nation: Nation;
  city: City;
  turn: TurnState;
  notifications: string[];
}
