const BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json();
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json();
}

export const api = {
  get,
  post,

  // My pages
  myPage: () => get('/api/my-page'),
  myGenerals: () => get('/api/my-generals'),
  myCities: () => get('/api/my-cities'),
  myBoss: () => get('/api/my-boss'),
  myNationDetail: () => get('/api/my-nation-detail'),
  city: (id: number) => get(`/api/city/${id}`),
  generals: () => get('/api/generals'),
  tournament: () => get('/api/tournament'),

  // Rankings
  rankings: {
    bestGenerals: () => get('/api/rankings/best-generals'),
    emperor: () => get('/api/rankings/emperor'),
    emperorDetail: (id: number) => get(`/api/rankings/emperor/${id}`),
    allGenerals: () => get('/api/rankings/generals'),
    kingdoms: () => get('/api/rankings/kingdoms'),
    npcs: () => get('/api/rankings/npcs'),
    hallOfFame: () => get('/api/rankings/hall-of-fame'),
    traffic: () => get('/api/rankings/traffic'),
  },

  // P6 pages
  auctions: () => get('/api/auctions'),
  betting: () => get('/api/bettings'),
  mailbox: () => get('/api/mailbox'),
  diplomacy: () => get('/api/diplomacy'),

  // Commands
  command: (code: string, args: unknown) => post(`/api/command/${code}`, args),
  availableCommands: () => get('/api/commands/available'),

  // Simulator
  simulateBattle: (body: unknown) => post('/api/simulate-battle', body),
};
