"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { frontApi } from "@/lib/gameApi";
import type { FrontInfoResponse } from "@/types";
import { MapViewer } from "@/components/game/map-viewer";
import { CommandPanel } from "@/components/game/command-panel";
import { CityBasicCard } from "@/components/game/city-basic-card";
import { NationBasicCard } from "@/components/game/nation-basic-card";
import { GeneralBasicCard } from "@/components/game/general-basic-card";
import { MainControlBar } from "@/components/game/main-control-bar";
import { MessagePanel } from "@/components/game/message-panel";
import { GameBottomBar } from "@/components/game/game-bottom-bar";
import { LoadingState } from "@/components/game/loading-state";
import { Button } from "@/components/ui/button";

export default function GameDashboard() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals } = useGameStore();
  const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
  const [lastRecordId, setLastRecordId] = useState<number | undefined>();
  const [lastHistoryId, setLastHistoryId] = useState<number | undefined>();
  const [loading, setLoading] = useState(true);

  const loadFrontInfo = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await frontApi.getInfo(
        currentWorld.id,
        lastRecordId,
        lastHistoryId,
      );
      setFrontInfo(data);

      const lastRecord = data.recentRecord.general[0]?.id;
      const lastHistory = data.recentRecord.history[0]?.id;
      if (lastRecord) setLastRecordId(lastRecord);
      if (lastHistory) setLastHistoryId(lastHistory);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld, lastRecordId, lastHistoryId]);

  useEffect(() => {
    loadFrontInfo();
  }, [currentWorld]); // eslint-disable-line react-hooks/exhaustive-deps

  // Compute user / NPC gen counts from genCount array
  const genCounts = useMemo(() => {
    if (!frontInfo?.global.genCount) return { user: 0, npc: 0 };
    let user = 0;
    let npc = 0;
    for (const [npcType, cnt] of frontInfo.global.genCount) {
      if (npcType < 2) user += cnt;
      else npc += cnt;
    }
    return { user, npc };
  }, [frontInfo?.global.genCount]);

  if (!currentWorld) return <LoadingState message="월드를 불러오는 중..." />;
  if (loading) return <LoadingState />;

  const global = frontInfo?.global;
  const mapCode =
    (currentWorld.config as Record<string, string>)?.mapCode ?? "che";

  return (
    <div id="container" className="pb-16 lg:pb-0">
      {/* ===== GameInfo header (legacy GameInfo.vue parity) ===== */}
      {global && (
        <>
          <h3 className="text-center font-bold py-1 text-sm">
            {global.scenarioText}{" "}
            <span style={{ color: "cyan" }}>
              {global.year}년 {global.month}월
            </span>{" "}
            ({global.turnTerm}분 턴)
          </h3>
          <div
            className="grid text-center text-xs border-t border-gray-600"
            style={{ gridTemplateColumns: "repeat(4, 1fr)" }}
          >
            <div
              className="border-b border-l border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              {global.scenarioText}
            </div>
            <div
              className="border-b border-l border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              NPC: {global.extendedGeneral ? "확장" : "표준"}{" "}
              {global.isFiction ? "가상" : "사실"}
            </div>
            <div
              className="border-b border-l border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              NPC선택:{" "}
              {["불가능", "가능", "선택 생성"][global.npcMode] ?? "불가능"}
            </div>
            <div
              className="border-b border-l border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              접속자: {global.onlineUserCnt}명
            </div>
            <div className="border-b border-l border-gray-600 py-1">
              {global.year}年 {global.month}月 ({global.turnTerm}분 턴)
            </div>
            <div className="border-b border-l border-gray-600 py-1">
              턴당 갱신: {global.apiLimit}회
            </div>
            <div className="border-b border-l border-gray-600 py-1 col-span-2">
              장수: 유저 {genCounts.user} / {global.generalCntLimit ?? "∞"} +{" "}
              <span style={{ color: "cyan" }}>NPC {genCounts.npc}명</span>
            </div>
            <div className="border-b border-l border-gray-600 py-1 col-span-2">
              {global.isTournamentActive ? (
                <span style={{ color: "cyan" }}>토너먼트 진행 중</span>
              ) : (
                <span style={{ color: "magenta" }}>토너먼트 없음</span>
              )}
            </div>
            <div
              className="border-b border-l border-gray-600 py-1"
              style={{ color: global.isLocked ? "magenta" : "cyan" }}
            >
              동작: {global.lastExecuted?.substring(11, 16) ?? "-"}
            </div>
            <div className="border-b border-l border-gray-600 py-1">
              {global.auctionCount ? (
                <span style={{ color: "cyan" }}>
                  {global.auctionCount}건 거래중
                </span>
              ) : (
                <span style={{ color: "magenta" }}>거래 없음</span>
              )}
            </div>
          </div>
        </>
      )}

      {/* ===== Online nations bar ===== */}
      {global && (
        <div className="border-t border-gray-600 px-2 py-1 text-xs">
          접속중인 국가:{" "}
          {global.onlineNations.map((n) => (
            <span key={n.id} className="mr-2">
              <span
                className="inline-block size-2 rounded-full mr-0.5"
                style={{ backgroundColor: n.color }}
              />
              {n.name}({n.genCount})
            </span>
          ))}
        </div>
      )}

      {/* ===== Online users bar ===== */}
      {frontInfo?.nation && (
        <div className="border-t border-gray-600 px-2 py-1 text-xs">
          【 접속자 】 {frontInfo.nation.onlineGen}
        </div>
      )}

      {/* ===== Nation notice ===== */}
      <div className="border-t border-gray-600 py-1">
        <div className="px-2 text-xs font-bold">【 국가방침 】</div>
        {frontInfo?.nation?.notice && (
          <div
            className="px-2 text-xs break-all"
            dangerouslySetInnerHTML={{ __html: frontInfo.nation.notice.msg }}
          />
        )}
      </div>

      {/* ===== Main game board (legacy ingameBoard grid) ===== */}
      <div className="ingameBoard" style={{ display: "grid" }}>
        {/* Map */}
        <div className="mapView">
          <MapViewer worldId={currentWorld.id} mapCode={mapCode} compact />
        </div>

        {/* Commands */}
        <div className="reservedCommandZone">
          {myGeneral && (
            <CommandPanel
              generalId={myGeneral.id}
              realtimeMode={currentWorld.realtimeMode}
            />
          )}
        </div>

        {/* Action buttons */}
        <div className="actionPlate p-2">
          <div className="flex gap-1">
            <Button
              variant="outline"
              size="sm"
              className="flex-[8]"
              onClick={loadFrontInfo}
            >
              갱 신
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="flex-[4]"
              onClick={() => (window.location.href = "/lobby")}
            >
              로비로
            </Button>
          </div>
        </div>

        {/* City info */}
        <div className="cityInfo">
          <CityBasicCard city={frontInfo?.city ?? null} />
        </div>

        {/* Controls toolbar */}
        <div className="generalCommandToolbar whitespace-nowrap">
          <MainControlBar />
        </div>

        {/* Nation info */}
        <div className="nationInfo">
          <NationBasicCard nation={frontInfo?.nation ?? null} global={global} />
        </div>

        {/* General info */}
        <div className="generalInfo" style={{ width: "100%", maxWidth: 500 }}>
          <GeneralBasicCard
            general={frontInfo?.general ?? null}
            nation={frontInfo?.nation ?? null}
            turnTerm={global?.turnTerm}
            lastExecuted={global?.lastExecuted}
          />
        </div>
      </div>

      {/* ===== Record zone (legacy parity: 동향 + 개인 side by side, 정세 full) ===== */}
      {frontInfo && (
        <div className="grid grid-cols-1 lg:grid-cols-2">
          <div>
            <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
              장수 동향
            </div>
            {frontInfo.recentRecord.global.length === 0 ? (
              <div className="px-2 py-1 text-xs text-gray-400">기록 없음</div>
            ) : (
              frontInfo.recentRecord.global.map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span> {r.message}
                </div>
              ))
            )}
          </div>
          <div>
            <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
              개인 기록
            </div>
            {frontInfo.recentRecord.general.length === 0 ? (
              <div className="px-2 py-1 text-xs text-gray-400">기록 없음</div>
            ) : (
              frontInfo.recentRecord.general.map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span> {r.message}
                </div>
              ))
            )}
          </div>
          <div className="col-span-1 lg:col-span-2">
            <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
              중원 정세
            </div>
            {frontInfo.recentRecord.history.length === 0 ? (
              <div className="px-2 py-1 text-xs text-gray-400">기록 없음</div>
            ) : (
              frontInfo.recentRecord.history.map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span> {r.message}
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {/* ===== Message panel ===== */}
      {myGeneral && (
        <MessagePanel
          worldId={currentWorld.id}
          myGeneralId={myGeneral.id}
          generals={generals}
        />
      )}

      {/* ===== Mobile bottom bar ===== */}
      <GameBottomBar onRefresh={loadFrontInfo} />

      {/* ===== Responsive grid styles for ingameBoard ===== */}
      <style jsx>{`
        #container {
          width: 100%;
          max-width: 500px;
          margin: 0 auto;
        }

        .ingameBoard {
          grid-template-columns: 1fr;
        }
        .actionPlate {
          order: -1;
        }
        .reservedCommandZone {
          order: 0;
        }
        .generalCommandToolbar {
          order: 1;
        }
        .nationInfo {
          order: 2;
        }
        .generalInfo {
          order: 3;
        }
        .cityInfo {
          order: 4;
        }
        .mapView {
          order: 5;
        }
        @media (min-width: 1000px) {
          #container {
            max-width: 1000px;
          }

          .ingameBoard {
            grid-template-columns: 500px 200px 300px;
          }
          .mapView {
            grid-column: 1 / 3;
            grid-row: 1;
            order: unset;
          }
          .reservedCommandZone {
            grid-column: 3;
            grid-row: 1 / 3;
            order: unset;
          }
          .cityInfo {
            grid-column: 1 / 3;
            grid-row: 2 / 4;
            order: unset;
          }
          .actionPlate {
            grid-column: 3;
            grid-row: 3;
            order: unset;
          }
          .generalCommandToolbar {
            grid-column: 1 / 4;
            order: unset;
          }
          .nationInfo {
            grid-column: 1;
            order: unset;
          }
          .generalInfo {
            grid-column: 2 / 4;
            order: unset;
          }
        }
      `}</style>
    </div>
  );
}
