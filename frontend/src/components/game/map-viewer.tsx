"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useGameStore } from "@/stores/gameStore";
import { GAME_CDN_ROOT, getNationBgUrl } from "@/lib/image";

interface MapViewerProps {
  worldId: number;
  mapCode?: string;
  compact?: boolean;
}

// [bgW, bgH, icnW, icnH, flagR, flagT]
const detailMapCitySizes: Record<number, number[]> = {
  1: [48, 45, 16, 15, -8, -4],
  2: [60, 42, 20, 14, -8, -4],
  3: [42, 42, 14, 14, -8, -4],
  4: [60, 45, 20, 15, -6, -3],
  5: [72, 48, 24, 16, -6, -4],
  6: [78, 54, 26, 18, -6, -4],
  7: [84, 60, 28, 20, -6, -4],
  8: [96, 72, 32, 24, -6, -3],
};

export function MapViewer({
  worldId,
  mapCode = "che",
  compact = false,
}: MapViewerProps) {
  const router = useRouter();
  const { cities, nations, mapData, loadAll, loadMap } = useGameStore();
  const [showNames, setShowNames] = useState(!compact);

  useEffect(() => {
    loadAll(worldId);
    loadMap(mapCode);
  }, [worldId, mapCode, loadAll, loadMap]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  if (!mapData) {
    return (
      <div className="flex items-center justify-center h-32 text-xs text-muted-foreground">
        지도 로딩중...
      </div>
    );
  }

  const smV = compact ? 500 / 700 : 1;
  const containerWidth = compact ? 500 : 700;
  const containerHeight = compact ? 357.14 : 500;

  // Determine Map Season
  const month = 1;
  let season = "spring";
  if (month >= 4 && month <= 6) season = "summer";
  else if (month >= 7 && month <= 9) season = "fall";
  else if (month >= 10 || month <= 12) season = "winter";

  const mapFolder = mapCode.includes("miniche")
    ? "che"
    : mapCode === "ludo_rathowm"
      ? "ludo_rathowm"
      : mapCode;
  const mapRoadImage = mapCode.includes("miniche")
    ? "miniche_road.png"
    : mapCode === "ludo_rathowm"
      ? "road.png"
      : `${mapCode}_road.png`;

  const mapLayerUrl = `${GAME_CDN_ROOT}/map/${mapFolder}/bg_${season}.jpg`;
  const mapRoadUrl = `${GAME_CDN_ROOT}/map/${mapFolder}/${mapRoadImage}`;

  const handleCityClick = (cityId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    router.push(`/city?id=${cityId}`);
  };

  return (
    <div
      className="relative w-full overflow-hidden bg-black text-[14px] text-white"
      style={{
        maxWidth: containerWidth,
        height: containerHeight,
        margin: "0 auto",
      }}
    >
      {!compact && (
        <button
          type="button"
          onClick={() => setShowNames(!showNames)}
          className="absolute right-1 bottom-1 z-10 border border-gray-600 bg-[#111] px-1.5 py-0.5 text-[10px] text-gray-300"
        >
          {showNames ? "이름 숨김" : "이름 표시"}
        </button>
      )}

      {/* Map Background Layers */}
      <div
        className="absolute inset-0 z-0 bg-no-repeat bg-center"
        style={{
          backgroundImage: `url('${mapLayerUrl}')`,
          backgroundSize: `${containerWidth}px ${containerHeight}px`,
        }}
      />
      <div
        className="absolute inset-0 z-[1] bg-no-repeat bg-center"
        style={{
          backgroundImage: `url('${mapRoadUrl}')`,
          backgroundSize: `${containerWidth}px ${containerHeight}px`,
        }}
      />

      {/* Map Cities */}
      <div className="absolute inset-0 z-[2]">
        {mapData.cities.map((cc) => {
          const rtCity = cityMap.get(cc.id);
          const nation = rtCity?.nationId
            ? nationMap.get(rtCity.nationId)
            : null;
          const myCity = false;

          const sizes = detailMapCitySizes[cc.level] || detailMapCitySizes[1];
          const bgW = sizes[0] * smV;
          const bgH = sizes[1] * smV;
          const icnW = sizes[2] * smV;
          const icnH = sizes[3] * smV;
          const flagR = sizes[4];
          const flagT = sizes[5];

          const left = cc.x * smV - 20;
          const top = cc.y * smV - (compact ? 18 : 15);

          return (
            <button
              key={cc.id}
              type="button"
              className="absolute h-[30px] w-[40px] cursor-pointer appearance-none border-0 bg-transparent p-0 text-left"
              style={{ left, top }}
              onClick={(e) => handleCityClick(cc.id, e)}
            >
              {/* Nation Color Blotch Base */}
              {nation?.color && (
                <div
                  className="absolute z-[1] bg-center bg-no-repeat"
                  style={{
                    backgroundImage: `url('${getNationBgUrl(nation.color)}')`,
                    backgroundSize: `${bgW}px ${bgH}px`,
                    width: bgW,
                    height: bgH,
                    left: (40 - bgW) / 2,
                    top: (30 - bgH) / 2,
                  }}
                />
              )}

              <div className="absolute z-[2] w-full h-full">
                {/* City Icon Container */}
                <div
                  className="absolute"
                  style={{
                    width: icnW,
                    height: icnH,
                    left: (40 - icnW) / 2,
                    top: (30 - icnH) / 2,
                  }}
                >
                    <img
                      src={`${GAME_CDN_ROOT}/cast_${cc.level}.gif`}
                    className="w-full h-full block"
                    alt=""
                  />

                  {/* My City Highlight */}
                  {myCity && (
                    <div className="absolute -inset-[2px] rounded-[33%] border-[4px] border-solid border-red-500 animate-pulse" />
                  )}

                  {/* Nation Flag and Capital Icon */}
                  {nation && (
                    <div
                      className="absolute"
                      style={{
                        right: flagR,
                        top: flagT,
                        width: 12 * smV,
                        height: 12 * smV,
                      }}
                    >
                      <img
                        src={`${GAME_CDN_ROOT}/${(rtCity?.supplyState ?? 0) > 0 ? "f" : "d"}${nation.color.substring(1).toUpperCase()}.gif`}
                        className="w-full h-full block"
                        alt=""
                      />
                      {nation.capitalCityId === cc.id && (
                        <div
                          className="absolute bg-yellow-400"
                          style={{
                            right: -1,
                            top: 0,
                            width: 10 * smV,
                            height: 10 * smV,
                          }}
                        >
                          <img
                            src={`${GAME_CDN_ROOT}/event51.gif`}
                            className="w-full h-full block"
                            alt="capital"
                          />
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* City State Icon */}
                {rtCity && rtCity.state > 0 && (
                  <div className="absolute left-0" style={{ top: 5 * smV }}>
                    <img
                      src={`${GAME_CDN_ROOT}/event${rtCity.state}.gif`}
                      className="object-contain"
                      style={{ width: 10 * smV }}
                      alt=""
                    />
                  </div>
                )}

                {/* City Name */}
                {showNames && (
                  <span
                    className={`absolute whitespace-nowrap text-white px-[2px] py-[1px] bg-black/50 ${compact ? "text-[10px]" : "text-[10px]"}`}
                    style={{
                      left: "70%",
                      bottom: compact ? -12 : -10,
                    }}
                  >
                    {cc.name}
                  </span>
                )}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
