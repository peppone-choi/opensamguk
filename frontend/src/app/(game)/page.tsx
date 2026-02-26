"use client";

import { useEffect, useRef, useState, useCallback, useMemo } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { frontApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
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
import { toast } from "sonner";
import { formatLog } from "@/lib/formatLog";

/** Format tournament type number to display text */
function formatTournamentType(type?: number | null): string {
  if (type === undefined || type === null) return "토너먼트";
  const types: Record<number, string> = {
    0: "통솔 토너먼트",
    1: "일기토 토너먼트",
    2: "설전 토너먼트",
  };
  return types[type] ?? "토너먼트";
}

/** Format autorun mode */
function formatAutorunMode(mode?: number): string {
  if (mode === undefined) return "일반";
  const modes: Record<number, string> = {
    0: "불가",
    1: "가능",
    2: "자동",
  };
  return modes[mode] ?? "일반";
}

export default function GameDashboard() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals } = useGameStore();
  const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
  const [lastRecordId, setLastRecordId] = useState<number | undefined>();
  const [lastHistoryId, setLastHistoryId] = useState<number | undefined>();
  const [loading, setLoading] = useState(true);
  const loadFrontInfoRef = useRef<() => Promise<void>>(async () => {});

  const [showVersionModal, setShowVersionModal] = useState(false);
  const [mobileTab, setMobileTab] = useState<
    "map" | "commands" | "status" | "world" | "messages"
  >("map");

  const mobileTabs = [
    { key: "map", label: "지도" },
    { key: "commands", label: "명령" },
    { key: "status", label: "상태" },
    { key: "world", label: "동향" },
    { key: "messages", label: "메시지" },
  ] as const;

  const isTabActive = (tab: string) => mobileTab === tab;

  const loadFrontInfo = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await frontApi.getInfo(
        currentWorld.id,
        lastRecordId,
        lastHistoryId,
      );
      setFrontInfo(data);

      // Save world month to localStorage for cross-page use (e.g., map season)
      if (data.global?.month) {
        try {
          localStorage.setItem(
            "opensam:world:month",
            String(data.global.month),
          );
        } catch {
          /* ignore */
        }
      }
      // Track lastVoteState for vote notification
      if (data.global?.lastVote) {
        try {
          const prevVoteState = localStorage.getItem("opensam:lastVoteState");
          const curVoteId = String(
            (data.global.lastVote as Record<string, unknown>)?.id ?? "",
          );
          if (prevVoteState !== curVoteId && curVoteId) {
            toast.info("새로운 설문이 진행중입니다!", { duration: 5000 });
          }
          if (curVoteId)
            localStorage.setItem("opensam:lastVoteState", curVoteId);
        } catch {
          /* ignore */
        }
      }

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
    loadFrontInfoRef.current = loadFrontInfo;
  }, [loadFrontInfo]);

  useEffect(() => {
    loadFrontInfo();
  }, [loadFrontInfo]);

  useEffect(() => {
    if (!currentWorld) return;

    const unsubTurn = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/turn`,
      () => {
        loadFrontInfoRef.current().catch(() => {});
      },
    );
    const unsubMessage = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/message`,
      () => {
        loadFrontInfoRef.current().catch(() => {});
      },
    );

    return () => {
      unsubTurn();
      unsubMessage();
    };
  }, [currentWorld]);

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
            {((currentWorld.config as Record<string, string>)
              ?.name as string) ?? global.scenarioText}{" "}
            {global.serverCnt > 0 && (
              <span className="text-muted-foreground">
                {global.serverCnt}기
              </span>
            )}{" "}
            <span style={{ color: "cyan" }}>{global.scenarioText}</span>
          </h3>
          <div className="grid grid-cols-12 text-center text-[11px] border-t border-b border-gray-600 bg-[#111]">
            <div
              className="col-span-8 lg:col-span-4 border-r border-b border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              {global.scenarioText}
            </div>
            <div
              className="col-span-4 lg:col-span-2 border-r border-b border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              NPC 수, 상성: {global.extendedGeneral ? "확장" : "표준"}{" "}
              {global.isFiction ? "가상" : "사실"}
            </div>
            <div
              className="col-span-4 lg:col-span-2 border-r border-b border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              NPC선택:{" "}
              {["불가능", "가능", "선택 생성"][global.npcMode] ?? "불가능"}
            </div>
            <div
              className="col-span-4 lg:col-span-2 border-r border-b border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              토너먼트: 경기당{" "}
              {Math.max(1, Math.round((global.turnTerm * 5) / 20))}분
            </div>
            <div
              className="col-span-4 lg:col-span-2 border-b border-gray-600 py-1"
              style={{ color: "cyan" }}
            >
              기타 설정:{" "}
              {formatAutorunMode(
                (global as unknown as Record<string, unknown>).autorunUser as
                  | number
                  | undefined,
              )}
            </div>

            <div className="col-span-8 lg:col-span-4 border-r border-b border-gray-600 py-1">
              현재: {global.year}年 {global.month}月 ({global.turnTerm}분 턴
              서버)
            </div>
            <div className="col-span-4 lg:col-span-2 border-r border-b border-gray-600 py-1">
              접속자: {(global.onlineUserCnt ?? 0).toLocaleString()}명
            </div>
            <div className="col-span-4 lg:col-span-2 border-r border-b border-gray-600 py-1">
              턴당 갱신횟수: {global.apiLimit?.toLocaleString()}회
            </div>
            <div className="col-span-8 lg:col-span-4 border-b border-gray-600 py-1">
              등록 장수: 유저 {genCounts.user.toLocaleString()} /{" "}
              {(global.generalCntLimit ?? Infinity).toLocaleString()} +{" "}
              <span style={{ color: "cyan" }}>
                NPC {genCounts.npc.toLocaleString()} 명
              </span>
            </div>

            <div className="col-span-6 lg:col-span-4 border-r border-gray-600 py-1">
              {global.isTournamentActive ? (
                <span style={{ color: "cyan" }}>
                  ↑{formatTournamentType(global.tournamentType)} 진행중
                  {global.tournamentTime
                    ? ` ${global.tournamentTime.substring(5, 16)}`
                    : ""}
                  ↑
                </span>
              ) : (
                <span style={{ color: "magenta" }}>
                  현재 토너먼트 경기 없음
                </span>
              )}
            </div>
            <div
              className="col-span-6 lg:col-span-2 border-r border-gray-600 py-1"
              style={{ color: global.isLocked ? "magenta" : "cyan" }}
            >
              동작 시각: {global.lastExecuted?.substring(5) ?? "-"}
            </div>
            <div className="col-span-6 lg:col-span-2 border-r border-gray-600 py-1">
              {global.auctionCount ? (
                <span style={{ color: "cyan" }}>
                  <a href="/auction" className="underline">
                    {global.auctionCount}건 거래 진행중
                  </a>
                </span>
              ) : (
                <span style={{ color: "magenta" }}>진행중인 거래 없음</span>
              )}
            </div>
            <div className="col-span-6 lg:col-span-4 py-1">
              {global.lastVote ? (
                <span style={{ color: "cyan" }}>
                  <a href="/vote" className="underline">
                    설문 진행 중:{" "}
                    <span>
                      {(global.lastVote as Record<string, string>)?.title ?? ""}
                    </span>
                  </a>
                </span>
              ) : (
                <span style={{ color: "magenta" }}>진행중인 설문 없음</span>
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

      {/* ===== Mobile Tabs ===== */}
      <div className="lg:hidden flex border-t border-b border-gray-600">
        {mobileTabs.map((tab) => (
          <button
            key={tab.key}
            className={`flex-1 py-1.5 text-xs font-bold text-center border-r border-gray-600 last:border-r-0 ${
              mobileTab === tab.key
                ? "bg-[#00582c] text-white"
                : "bg-[#111] text-gray-400"
            }`}
            onClick={() => setMobileTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* ===== Main game board (legacy ingameBoard grid) ===== */}
      <div className="ingameBoard" style={{ display: "grid" }}>
        {/* Map */}
        <div className={`mapView ${isTabActive("map") ? "" : "max-lg:hidden"}`}>
          <MapViewer worldId={currentWorld.id} mapCode={mapCode} compact />
        </div>

        {/* Commands */}
        <div
          className={`reservedCommandZone ${isTabActive("commands") ? "" : "max-lg:hidden"}`}
        >
          {myGeneral && (
            <CommandPanel
              generalId={myGeneral.id}
              realtimeMode={currentWorld.realtimeMode}
            />
          )}
        </div>

        {/* Action buttons */}
        <div
          className={`actionPlate p-2 ${isTabActive("commands") ? "" : "max-lg:hidden"}`}
        >
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
        <div
          className={`cityInfo ${isTabActive("status") ? "" : "max-lg:hidden"}`}
        >
          <CityBasicCard city={frontInfo?.city ?? null} />
        </div>

        {/* Controls toolbar */}
        <div
          className={`generalCommandToolbar whitespace-nowrap ${isTabActive("commands") ? "" : "max-lg:hidden"}`}
        >
          <MainControlBar />
        </div>

        {/* Nation info */}
        <div
          className={`nationInfo ${isTabActive("status") ? "" : "max-lg:hidden"}`}
        >
          <NationBasicCard nation={frontInfo?.nation ?? null} global={global} />
        </div>

        {/* General info */}
        <div
          className={`generalInfo ${isTabActive("status") ? "" : "max-lg:hidden"}`}
          style={{ width: "100%", maxWidth: 500 }}
        >
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
        <div
          className={`grid grid-cols-1 lg:grid-cols-2 ${isTabActive("world") ? "" : "max-lg:hidden"}`}
        >
          <div>
            <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
              장수 동향
            </div>
            {frontInfo.recentRecord.global.length === 0 ? (
              <div className="px-2 py-1 text-xs text-gray-400">기록 없음</div>
            ) : (
              frontInfo.recentRecord.global.slice(0, 15).map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span>{" "}
                  {formatLog(r.message)}
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
              frontInfo.recentRecord.general.slice(0, 15).map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span>{" "}
                  {formatLog(r.message)}
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
              frontInfo.recentRecord.history.slice(0, 15).map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-2 py-0.5 text-xs"
                >
                  <span className="text-gray-400">[{r.date}]</span>{" "}
                  {formatLog(r.message)}
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {/* ===== Nation Power Summary ===== */}
      {global && global.onlineNations.length > 0 && (
        <div className={`${isTabActive("world") ? "" : "max-lg:hidden"}`}>
          <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
            세력 현황
          </div>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-px bg-gray-600/30">
            {global.onlineNations
              .sort((a, b) => b.genCount - a.genCount)
              .map((n) => (
                <div
                  key={n.id}
                  className="bg-[#111] px-2 py-1.5 flex items-center gap-2"
                >
                  <span
                    className="inline-block size-3 rounded-full shrink-0"
                    style={{ backgroundColor: n.color }}
                  />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-medium truncate">{n.name}</p>
                    <p className="text-[10px] text-muted-foreground">
                      장수 {n.genCount}명
                    </p>
                  </div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* ===== General Status Summary ===== */}
      {frontInfo?.general && (
        <div className={`${isTabActive("status") ? "" : "max-lg:hidden"}`}>
          <div className="legacy-bg1 text-center border-t border-b border-gray-600 text-xs font-bold py-0.5">
            내 장수 요약
          </div>
          <div className="grid grid-cols-3 lg:grid-cols-6 text-center text-[11px] border-b border-gray-600">
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">전투</span>{" "}
              <span className="text-cyan-400">
                {frontInfo.general.warnum}전 {frontInfo.general.killnum}승{" "}
                {frontInfo.general.deathnum}패
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">살상</span>{" "}
              <span className="text-orange-400">
                {frontInfo.general.killcrew.toLocaleString()}
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">피살</span>{" "}
              <span className="text-red-400">
                {frontInfo.general.deathcrew.toLocaleString()}
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">계략</span>{" "}
              <span className="text-purple-400">
                {frontInfo.general.firenum}
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">부상</span>{" "}
              <span
                className={
                  frontInfo.general.injury > 0
                    ? "text-red-400"
                    : "text-green-400"
                }
              >
                {frontInfo.general.injury}%
              </span>
            </div>
            <div className="py-1">
              <span className="text-muted-foreground">명성</span>{" "}
              <span className="text-yellow-400">
                {frontInfo.general.honorText}
              </span>
            </div>
          </div>
          <div className="grid grid-cols-5 text-center text-[11px] border-b border-gray-600">
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">숙련</span>{" "}
              <span className="text-cyan-300">
                보{frontInfo.general.dex1} 궁{frontInfo.general.dex2} 기
                {frontInfo.general.dex3} 공{frontInfo.general.dex4} 수
                {frontInfo.general.dex5}
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1 col-span-2">
              <span className="text-muted-foreground">특기</span>{" "}
              <span className="text-green-300">
                {frontInfo.general.personal || "-"} /{" "}
                {frontInfo.general.specialDomestic || "-"} /{" "}
                {frontInfo.general.specialWar || "-"}
              </span>
            </div>
            <div className="border-r border-gray-600/50 py-1">
              <span className="text-muted-foreground">아이템</span>{" "}
              <span className="text-yellow-300">
                {[
                  frontInfo.general.weapon,
                  frontInfo.general.book,
                  frontInfo.general.horse,
                  frontInfo.general.item,
                ]
                  .filter(Boolean)
                  .join(", ") || "없음"}
              </span>
            </div>
            <div className="py-1">
              <span className="text-muted-foreground">경험/공헌</span>{" "}
              <span>
                {frontInfo.general.explevel}/{frontInfo.general.dedlevel}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* ===== Message panel ===== */}
      {myGeneral && (
        <div
          className={`mt-2 ${isTabActive("messages") ? "" : "max-lg:hidden"}`}
        >
          <MessagePanel
            worldId={currentWorld.id}
            myGeneralId={myGeneral.id}
            generals={generals}
          />
        </div>
      )}

      {/* ===== Version Info ===== */}
      {global && (
        <div
          className={`text-center py-1 ${isTabActive("world") ? "" : "max-lg:hidden"}`}
        >
          <button
            type="button"
            className="text-[10px] text-gray-500 hover:text-gray-300"
            onClick={() => setShowVersionModal(true)}
          >
            버전 정보
          </button>
        </div>
      )}
      {showVersionModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => setShowVersionModal(false)}
        >
          <div
            className="bg-[#222] border border-gray-600 rounded-lg p-4 max-w-sm w-full mx-4 space-y-2"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-sm font-bold text-center">버전 정보</h3>
            <div className="text-xs space-y-1">
              <p>
                <span className="text-muted-foreground">시나리오:</span>{" "}
                {global?.scenarioText}
              </p>
              <p>
                <span className="text-muted-foreground">서버:</span>{" "}
                {currentWorld?.name}
              </p>
              <p>
                <span className="text-muted-foreground">턴 주기:</span>{" "}
                {global?.turnTerm}분
              </p>
              <p>
                <span className="text-muted-foreground">게임 시간:</span>{" "}
                {global?.year}年 {global?.month}月
              </p>
              <p>
                <span className="text-muted-foreground">최종 실행:</span>{" "}
                {global?.lastExecuted ?? "-"}
              </p>
              <p>
                <span className="text-muted-foreground">확장 장수:</span>{" "}
                {global?.extendedGeneral ? "활성" : "비활성"}
              </p>
              <p>
                <span className="text-muted-foreground">가상/사실:</span>{" "}
                {global?.isFiction ? "가상" : "사실"}
              </p>
              <p>
                <span className="text-muted-foreground">NPC 모드:</span>{" "}
                {["불가", "가능", "선택생성"][global?.npcMode ?? 0]}
              </p>
              <p>
                <span className="text-muted-foreground">장수 제한:</span>{" "}
                {global?.generalCntLimit?.toLocaleString() ?? "무제한"}
              </p>
            </div>
            <div className="flex justify-center pt-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() => setShowVersionModal(false)}
              >
                닫기
              </Button>
            </div>
          </div>
        </div>
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
        @media (min-width: 1024px) {
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
