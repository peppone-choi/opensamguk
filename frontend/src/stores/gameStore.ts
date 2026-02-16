import { create } from "zustand";
import type { City, Nation, General, Diplomacy, MapData } from "@/types";
import {
  cityApi,
  nationApi,
  generalApi,
  diplomacyApi,
  mapApi,
} from "@/lib/gameApi";

interface GameStore {
  cities: City[];
  nations: Nation[];
  generals: General[];
  diplomacy: Diplomacy[];
  mapData: MapData | null;
  loading: boolean;

  loadAll: (worldId: number) => Promise<void>;
  loadMap: (mapName: string) => Promise<void>;
}

export const useGameStore = create<GameStore>((set) => ({
  cities: [],
  nations: [],
  generals: [],
  diplomacy: [],
  mapData: null,
  loading: false,

  loadAll: async (worldId) => {
    set({ loading: true });
    try {
      const [cities, nations, generals, diplomacy] = await Promise.all([
        cityApi.listByWorld(worldId),
        nationApi.listByWorld(worldId),
        generalApi.listByWorld(worldId),
        diplomacyApi.listByWorld(worldId),
      ]);
      set({
        cities: cities.data,
        nations: nations.data,
        generals: generals.data,
        diplomacy: diplomacy.data,
      });
    } finally {
      set({ loading: false });
    }
  },

  loadMap: async (mapName) => {
    const { data } = await mapApi.get(mapName);
    set({ mapData: data });
  },
}));
