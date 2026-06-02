const BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json() as Promise<T>;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json() as Promise<T>;
}

export const api = {
  get,
  post,

  // My pages
  myPage: <T>() => get<T>('/api/my-page'),
  myGenerals: <T>() => get<T>('/api/my-generals'),
  myCities: <T>() => get<T>('/api/my-cities'),
  myBoss: <T>() => get<T>('/api/my-boss'),
  myNationDetail: <T>() => get<T>('/api/my-nation-detail'),
  city: <T>(id: number) => get<T>(`/api/city/${id}`),
  generals: <T>() => get<T>('/api/generals'),
  tournament: <T>() => get<T>('/api/tournament'),

  // Rankings
  rankings: {
    bestGenerals: <T>() => get<T>('/api/rankings/best-generals'),
    emperor: <T>() => get<T>('/api/rankings/emperor'),
    emperorDetail: <T>(id: number) => get<T>(`/api/rankings/emperor/${id}`),
    allGenerals: <T>() => get<T>('/api/rankings/generals'),
    kingdoms: <T>() => get<T>('/api/rankings/kingdoms'),
    npcs: <T>() => get<T>('/api/rankings/npcs'),
    hallOfFame: <T>() => get<T>('/api/rankings/hall-of-fame'),
    traffic: <T>() => get<T>('/api/rankings/traffic'),
  },

  // P6 pages
  auctions: <T>() => get<T>('/api/auctions'),
  betting: <T>() => get<T>('/api/bettings'),
  mailbox: <T>() => get<T>('/api/mailbox'),
  diplomacy: <T>() => get<T>('/api/diplomacy'),

  // Commands
  command: <T>(code: string, args: unknown) => post<T>(`/api/command/${code}`, args),
  availableCommands: <T>() => get<T>('/api/commands/available'),

  // Simulator
  simulateBattle: <T>(body: unknown) => post<T>('/api/simulate-battle', body),
};
