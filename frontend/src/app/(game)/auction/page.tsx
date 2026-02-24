"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Gavel, Package, Clock, TrendingUp } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Message, General } from "@/types";
import { auctionApi } from "@/lib/gameApi";
import { formatLog } from "@/lib/formatLog";

/* ── payload shape (stored in Message.payload) ── */
interface AuctionPayload {
  type?: string;
  subType?: string; // "buyRice" | "sellRice"
  sellerId?: number;
  sellerName?: string;
  item?: string;
  amount?: number;
  minPrice?: number;
  currentBid?: number;
  currentBidderId?: number;
  currentBidderName?: string;
  bidCount?: number;
  endTime?: string;
  finishBidAmount?: number;
  closeTurnCnt?: number;
  status?: string;
  itemName?: string;
  itemStats?: Record<string, number>;
  // unique auction fields
  title?: string;
  hostName?: string;
  isCallerHost?: boolean;
  highestBid?: { generalName: string; amount: number; isCallerHighestBidder?: boolean };
  remainCloseDateExtensionCnt?: number;
  finished?: boolean;
  obfuscatedName?: string;
  target?: string;
  recentLogs?: string[];
  bidList?: { generalName: string; amount: number; date: string; isCallerHighestBidder?: boolean }[];
  remainPoint?: number;
}

const RESOURCE_LABELS: Record<string, string> = {
  gold: "금",
  rice: "쌀",
  crew: "병사",
};
const RESOURCE_COLORS: Record<string, string> = {
  gold: "text-yellow-400",
  rice: "text-green-400",
  crew: "text-blue-400",
};

function p(msg: Message): AuctionPayload {
  return (msg.payload ?? {}) as AuctionPayload;
}

function remaining(endTime?: string): string {
  if (!endTime) return "-";
  const diff = new Date(endTime).getTime() - Date.now();
  if (diff <= 0) return "종료";
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
}

function cutDateTime(dateTime: string, showSecond = false): string {
  if (showSecond) return dateTime.substring(5, 19);
  return dateTime.substring(5, 16);
}

function isActive(payload: AuctionPayload): boolean {
  if (payload.status === "completed" || payload.status === "cancelled" || payload.finished)
    return false;
  if (payload.endTime && new Date(payload.endTime).getTime() <= Date.now())
    return false;
  return true;
}

export default function AuctionPage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals, loadAll } = useGameStore();
  const [auctions, setAuctions] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [bidAmounts, setBidAmounts] = useState<Record<number, string>>({});
  const [bidding, setBidding] = useState<number | null>(null);

  // create-form state (resource auction - legacy parity)
  const [showCreate, setShowCreate] = useState(false);
  const [createSubType, setCreateSubType] = useState<"buyRice" | "sellRice">("buyRice");
  const [createAmount, setCreateAmount] = useState("1000");
  const [createStartBid, setCreateStartBid] = useState("500");
  const [createFinishBid, setCreateFinishBid] = useState("2000");
  const [createCloseTurnCnt, setCreateCloseTurnCnt] = useState("24");
  const [creating, setCreating] = useState(false);

  // Selected auction for detail view (unique items)
  const [selectedAuctionId, setSelectedAuctionId] = useState<number | null>(null);

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /* ── load ── */
  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await auctionApi.list(currentWorld.id);
      setAuctions(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
    load();
  }, [currentWorld, load, loadAll]);

  useEffect(() => {
    if (!currentWorld) return;
    timerRef.current = setInterval(load, 5_000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [currentWorld, load]);

  /* ── helpers ── */
  const genMap = new Map(generals.map((g) => [g.id, g]));

  const handleBid = async (auctionId: number) => {
    if (!myGeneral) return;
    const amount = Number(bidAmounts[auctionId]);
    if (!amount || amount <= 0) return;
    setBidding(auctionId);
    try {
      await auctionApi.bid(auctionId, myGeneral.id, amount);
      setBidAmounts((prev) => ({ ...prev, [auctionId]: "" }));
      await load();
    } catch {
      /* ignore */
    } finally {
      setBidding(null);
    }
  };

  const handleCreate = async () => {
    if (!currentWorld || !myGeneral) return;
    const amount = Number(createAmount);
    const startBid = Number(createStartBid);
    const finishBid = Number(createFinishBid);
    const closeTurnCnt = Number(createCloseTurnCnt);
    if (!amount || !startBid) return;
    setCreating(true);
    try {
      await auctionApi.create(currentWorld.id, {
        type: "resource",
        sellerId: myGeneral.id,
        item: createSubType,
        amount,
        minPrice: startBid,
        finishBidAmount: finishBid,
        closeTurnCnt,
      } as Parameters<typeof auctionApi.create>[1]);
      setShowCreate(false);
      setCreateAmount("1000");
      setCreateStartBid("500");
      setCreateFinishBid("2000");
      setCreateCloseTurnCnt("24");
      await load();
    } catch {
      /* ignore */
    } finally {
      setCreating(false);
    }
  };

  /* ── early returns ── */
  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  /* ── partition ── */
  const resourceAuctions = auctions.filter(
    (a) => (p(a).type ?? "resource") === "resource",
  );
  const itemAuctions = auctions.filter((a) => p(a).type === "item" || p(a).type === "unique");

  // Split resource by subType (buyRice = buying rice with gold, sellRice = selling rice for gold)
  const buyRiceAuctions = resourceAuctions.filter((a) => p(a).subType === "buyRice" || p(a).item === "buyRice");
  const sellRiceAuctions = resourceAuctions.filter((a) => p(a).subType === "sellRice" || p(a).item === "sellRice");
  const otherResourceAuctions = resourceAuctions.filter((a) => {
    const d = p(a);
    return d.subType !== "buyRice" && d.subType !== "sellRice" && d.item !== "buyRice" && d.item !== "sellRice";
  });

  const activeBuyRice = buyRiceAuctions.filter((a) => isActive(p(a)));
  const activeSellRice = sellRiceAuctions.filter((a) => isActive(p(a)));
  const activeOtherResource = otherResourceAuctions.filter((a) => isActive(p(a)));
  const activeItem = itemAuctions.filter((a) => isActive(p(a)));
  const doneItem = itemAuctions.filter((a) => !isActive(p(a)));

  // recent logs from any auction that has them
  const recentLogs = auctions.flatMap((a) => p(a).recentLogs ?? []).slice(0, 20);

  const mySelling = auctions.filter(
    (a) => p(a).sellerId === myGeneral?.id && isActive(p(a)),
  );
  const myBidding = auctions.filter(
    (a) =>
      p(a).currentBidderId === myGeneral?.id &&
      p(a).sellerId !== myGeneral?.id &&
      isActive(p(a)),
  );

  const selectedAuction = selectedAuctionId ? auctions.find((a) => a.id === selectedAuctionId) : null;

  return (
    <div className="space-y-0 max-w-4xl mx-auto">
      <PageHeader icon={Gavel} title="경매장" />

      <Tabs defaultValue="resource" className="legacy-page-wrap">
        <TabsList className="w-full justify-start border-b border-gray-600">
          <TabsTrigger value="resource">
            <TrendingUp className="size-3.5 mr-1" />
            금/쌀
          </TabsTrigger>
          <TabsTrigger value="item">
            <Package className="size-3.5 mr-1" />
            유니크
          </TabsTrigger>
        </TabsList>

        {/* ═══ Tab 1: Resource (legacy parity: buyRice / sellRice split) ═══ */}
        <TabsContent value="resource" className="mt-4 space-y-4 px-2">
          {/* my auctions summary */}
          {myGeneral && (mySelling.length > 0 || myBidding.length > 0) && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">내 경매</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {mySelling.length > 0 && (
                  <div>
                    <p className="text-xs text-muted-foreground mb-1">판매중</p>
                    {mySelling.map((a) => {
                      const d = p(a);
                      return (
                        <div
                          key={a.id}
                          className="flex items-center gap-2 text-xs border border-gray-700 rounded px-2 py-1"
                        >
                          <span className={RESOURCE_COLORS[d.item ?? ""] ?? ""}>
                            {RESOURCE_LABELS[d.item ?? ""] ?? d.itemName ?? d.item}
                          </span>
                          <span>{(d.amount ?? 0).toLocaleString()}</span>
                          <span className="ml-auto text-muted-foreground">
                            현재가: {(d.currentBid ?? d.minPrice ?? 0).toLocaleString()}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
                {myBidding.length > 0 && (
                  <div>
                    <p className="text-xs text-muted-foreground mb-1">입찰중</p>
                    {myBidding.map((a) => {
                      const d = p(a);
                      return (
                        <div
                          key={a.id}
                          className="flex items-center gap-2 text-xs border border-gray-700 rounded px-2 py-1"
                        >
                          <span className={RESOURCE_COLORS[d.item ?? ""] ?? ""}>
                            {RESOURCE_LABELS[d.item ?? ""] ?? d.itemName ?? d.item}
                          </span>
                          <span>내 입찰: {(d.currentBid ?? 0).toLocaleString()}</span>
                          <span className="ml-auto text-muted-foreground">
                            <Clock className="inline size-3 mr-0.5" />
                            {remaining(d.endTime)}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* Buy Rice section (쌀 구매 - legacy orange header) */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-orange-400">쌀 구매</CardTitle>
            </CardHeader>
            <CardContent>
              {activeBuyRice.length === 0 && activeOtherResource.length === 0 ? (
                <p className="text-xs text-muted-foreground">진행중인 쌀 구매 경매가 없습니다.</p>
              ) : (
                <ResourceAuctionTable
                  auctions={[...activeBuyRice, ...activeOtherResource.filter(a => p(a).item === "rice")]}
                  myId={myGeneral?.id}
                  bidAmounts={bidAmounts}
                  setBidAmounts={setBidAmounts}
                  onBid={handleBid}
                  bidding={bidding}
                  genMap={genMap}
                  unitLabel="쌀"
                  bidUnitLabel="금"
                />
              )}
            </CardContent>
          </Card>

          {/* Sell Rice section (쌀 판매 - legacy skyblue header) */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-sky-400">쌀 판매</CardTitle>
            </CardHeader>
            <CardContent>
              {activeSellRice.length === 0 ? (
                <p className="text-xs text-muted-foreground">진행중인 쌀 판매 경매가 없습니다.</p>
              ) : (
                <ResourceAuctionTable
                  auctions={activeSellRice}
                  myId={myGeneral?.id}
                  bidAmounts={bidAmounts}
                  setBidAmounts={setBidAmounts}
                  onBid={handleBid}
                  bidding={bidding}
                  genMap={genMap}
                  unitLabel="금"
                  bidUnitLabel="쌀"
                />
              )}
            </CardContent>
          </Card>

          {/* Other active resources (gold, crew, etc.) */}
          {activeOtherResource.filter(a => p(a).item !== "rice").length > 0 && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">기타 자원 경매</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-2">
                  {activeOtherResource.filter(a => p(a).item !== "rice").map((a) => (
                    <AuctionRow
                      key={a.id}
                      auction={a}
                      myId={myGeneral?.id}
                      bidVal={bidAmounts[a.id] ?? ""}
                      onBidVal={(v) => setBidAmounts((x) => ({ ...x, [a.id]: v }))}
                      onBid={() => handleBid(a.id)}
                      isBidding={bidding === a.id}
                      genMap={genMap}
                    />
                  ))}
                </div>
              </CardContent>
            </Card>
          )}

          {/* create button */}
          {myGeneral && (
            <div className="flex justify-end">
              <Button
                size="sm"
                variant={showCreate ? "outline" : "default"}
                onClick={() => setShowCreate(!showCreate)}
              >
                {showCreate ? "취소" : "경매 등록"}
              </Button>
            </div>
          )}

          {/* Legacy-parity create form with buyRice/sellRice, closeTurnCnt, finishBidAmount */}
          {showCreate && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">경매 등록</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">매물</label>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant={createSubType === "buyRice" ? "default" : "outline"}
                      onClick={() => setCreateSubType("buyRice")}
                    >
                      쌀
                    </Button>
                    <Button
                      size="sm"
                      variant={createSubType === "sellRice" ? "default" : "outline"}
                      onClick={() => setCreateSubType("sellRice")}
                    >
                      금
                    </Button>
                  </div>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    수량 ({createSubType === "buyRice" ? "쌀" : "금"})
                  </label>
                  <Input
                    type="number"
                    value={createAmount}
                    onChange={(e) => setCreateAmount(e.target.value)}
                    min={100}
                    max={10000}
                    step={10}
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">기간 (턴)</label>
                  <Input
                    type="number"
                    value={createCloseTurnCnt}
                    onChange={(e) => setCreateCloseTurnCnt(e.target.value)}
                    min={3}
                    max={24}
                    step={1}
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    시작가 ({createSubType === "buyRice" ? "금" : "쌀"})
                  </label>
                  <Input
                    type="number"
                    value={createStartBid}
                    onChange={(e) => setCreateStartBid(e.target.value)}
                    min={100}
                    max={10000}
                    step={10}
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    마감가 ({createSubType === "buyRice" ? "금" : "쌀"})
                  </label>
                  <Input
                    type="number"
                    value={createFinishBid}
                    onChange={(e) => setCreateFinishBid(e.target.value)}
                    min={100}
                    max={10000}
                    step={10}
                  />
                </div>
                <Button
                  onClick={handleCreate}
                  disabled={creating || !createAmount || !createStartBid}
                >
                  {creating ? "등록 중..." : "등록"}
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Recent auction logs (legacy parity) */}
          {recentLogs.length > 0 && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">이전 경매 (최근 20건)</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="max-h-48 overflow-y-auto space-y-0.5 text-xs">
                  {recentLogs.map((log, idx) => (
                    <div key={idx} className="text-gray-300">{formatLog(log)}</div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* ═══ Tab 2: Unique Item Auctions (legacy parity) ═══ */}
        <TabsContent value="item" className="mt-4 space-y-4 px-2">
          {/* Selected auction detail */}
          {selectedAuction && (() => {
            const d = p(selectedAuction);
            return (
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm">경매 {selectedAuction.id}번 상세</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-xs">
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
                    <div>
                      <span className="text-muted-foreground">경매명: </span>
                      <span className="text-amber-400 font-bold">{d.title ?? d.itemName ?? d.item}</span>
                    </div>
                    <div>
                      <span className="text-muted-foreground">주최자: </span>
                      <span className={d.isCallerHost ? "text-cyan-400 font-bold" : ""}>{d.hostName ?? d.sellerName ?? "-"}</span>
                    </div>
                    <div>
                      <span className="text-muted-foreground">종료일시: </span>
                      <span className="tabular-nums">{d.endTime ? cutDateTime(d.endTime, true) : "-"}</span>
                    </div>
                    <div>
                      <span className="text-muted-foreground">상태: </span>
                      <span>{isActive(d) ? "진행중" : "종료"}</span>
                    </div>
                  </div>

                  {/* Bid list */}
                  {d.bidList && d.bidList.length > 0 && (
                    <div>
                      <div className="text-muted-foreground mb-1 font-medium">입찰자 목록</div>
                      <div className="border border-gray-700 rounded overflow-hidden">
                        <div className="grid grid-cols-3 gap-2 text-center bg-gray-800 py-1 px-2 font-medium">
                          <div>입찰자</div>
                          <div className="text-right">입찰포인트</div>
                          <div>시각</div>
                        </div>
                        {d.bidList.map((bid, idx) => (
                          <div key={idx} className="grid grid-cols-3 gap-2 text-center py-0.5 px-2 border-t border-gray-800">
                            <div className={bid.isCallerHighestBidder ? "text-cyan-400 font-bold" : ""}>{bid.generalName}</div>
                            <div className="text-right tabular-nums">{bid.amount.toLocaleString()}</div>
                            <div className="tabular-nums">{cutDateTime(bid.date)}</div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Bid input */}
                  {isActive(d) && myGeneral && (
                    <div className="flex items-center gap-2">
                      {d.remainPoint != null && (
                        <span className="text-muted-foreground">잔여: {d.remainPoint.toLocaleString()}포인트</span>
                      )}
                      <Input
                        type="number"
                        placeholder="입찰 포인트"
                        value={bidAmounts[selectedAuction.id] ?? ""}
                        onChange={(e) => setBidAmounts((x) => ({ ...x, [selectedAuction.id]: e.target.value }))}
                        className="h-8 w-32 text-xs"
                      />
                      <Button
                        size="sm"
                        onClick={() => handleBid(selectedAuction.id)}
                        disabled={bidding === selectedAuction.id || !bidAmounts[selectedAuction.id]}
                        className="h-8 text-xs"
                      >
                        {bidding === selectedAuction.id ? "입찰중..." : "입찰"}
                      </Button>
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })()}

          {/* Ongoing unique auctions list */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">진행중인 경매 목록</CardTitle>
            </CardHeader>
            <CardContent>
              {activeItem.length === 0 ? (
                <EmptyState icon={Package} title="진행중인 아이템 경매가 없습니다." />
              ) : (
                <div className="border border-gray-700 rounded overflow-hidden text-xs">
                  <div className="grid grid-cols-7 gap-1 text-center bg-gray-800 py-1 px-1 font-medium">
                    <div>번호</div>
                    <div className="col-span-2">경매명</div>
                    <div>주최자</div>
                    <div>종료일시</div>
                    <div>1순위</div>
                    <div className="text-right pr-2">포인트</div>
                  </div>
                  {activeItem.map((a) => {
                    const d = p(a);
                    const hb = d.highestBid;
                    const isAnonymous = !!d.obfuscatedName;
                    return (
                      <div
                        key={a.id}
                        className="grid grid-cols-7 gap-1 text-center py-1 px-1 border-t border-gray-800 cursor-pointer hover:bg-white/5"
                        onClick={() => setSelectedAuctionId(a.id)}
                      >
                        <div className="flex items-center justify-center gap-1">
                          {a.id}
                          {isAnonymous && (
                            <span className="text-[9px] text-purple-400" title="익명 입찰">🔒</span>
                          )}
                        </div>
                        <div className="col-span-2 text-amber-400 truncate">{d.title ?? d.itemName ?? d.item}</div>
                        <div className={d.isCallerHost ? "text-cyan-400 font-bold" : ""}>{d.hostName ?? d.sellerName ?? "-"}</div>
                        <div className="tabular-nums">{d.endTime ? cutDateTime(d.endTime) : "-"}</div>
                        <div className={hb?.isCallerHighestBidder ? "text-cyan-400 font-bold" : ""}>
                          {isAnonymous ? (d.obfuscatedName ?? "익명") : (hb?.generalName ?? "-")}
                        </div>
                        <div className="text-right pr-2 tabular-nums">{(hb?.amount ?? d.currentBid ?? 0).toLocaleString()}</div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Finished unique auctions list */}
          {doneItem.length > 0 && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">종료된 경매 목록</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="border border-gray-700 rounded overflow-hidden text-xs">
                  <div className="grid grid-cols-7 gap-1 text-center bg-gray-800 py-1 px-1 font-medium">
                    <div>번호</div>
                    <div className="col-span-2">경매명</div>
                    <div>주최자</div>
                    <div>종료일시</div>
                    <div>1순위</div>
                    <div className="text-right pr-2">포인트</div>
                  </div>
                  {doneItem.slice(0, 20).map((a) => {
                    const d = p(a);
                    const hb = d.highestBid;
                    const statusLabel =
                      d.status === "cancelled" || (!hb && d.finished)
                        ? "유찰"
                        : hb?.isCallerHighestBidder
                          ? "낙찰"
                          : hb
                            ? "패찰"
                            : "종료";
                    const statusColor =
                      statusLabel === "낙찰"
                        ? "text-green-400"
                        : statusLabel === "유찰"
                          ? "text-gray-400"
                          : statusLabel === "패찰"
                            ? "text-red-400"
                            : "text-muted-foreground";
                    return (
                      <div
                        key={a.id}
                        className="grid grid-cols-7 gap-1 text-center py-1 px-1 border-t border-gray-800 cursor-pointer hover:bg-white/5 opacity-70"
                        onClick={() => setSelectedAuctionId(a.id)}
                      >
                        <div className="flex items-center justify-center gap-1">
                          {a.id}
                          <span className={`text-[9px] font-bold ${statusColor}`}>
                            {statusLabel}
                          </span>
                        </div>
                        <div className="col-span-2 truncate">{d.title ?? d.itemName ?? d.item}</div>
                        <div className={d.isCallerHost ? "text-cyan-400 font-bold" : ""}>{d.hostName ?? d.sellerName ?? "-"}</div>
                        <div className="tabular-nums">{d.endTime ? cutDateTime(d.endTime) : "-"}</div>
                        <div className={hb?.isCallerHighestBidder ? "text-cyan-400 font-bold" : ""}>{hb?.generalName ?? "-"}</div>
                        <div className="text-right pr-2 tabular-nums">{(hb?.amount ?? d.currentBid ?? 0).toLocaleString()}</div>
                      </div>
                    );
                  })}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ── Resource Auction Table (legacy parity: columns matching v_auction) ── */
function ResourceAuctionTable({
  auctions,
  myId,
  bidAmounts,
  setBidAmounts,
  onBid,
  bidding,
  genMap,
  unitLabel,
  bidUnitLabel,
}: {
  auctions: Message[];
  myId?: number;
  bidAmounts: Record<number, string>;
  setBidAmounts: React.Dispatch<React.SetStateAction<Record<number, string>>>;
  onBid: (id: number) => void;
  bidding: number | null;
  genMap: Map<number, General>;
  unitLabel: string;
  bidUnitLabel: string;
}) {
  const [selectedId, setSelectedId] = useState<number | null>(null);

  if (auctions.length === 0) return null;

  return (
    <div className="space-y-2">
      {/* Table header */}
      <div className="grid grid-cols-8 gap-1 text-center text-[10px] text-muted-foreground font-medium border-b border-gray-700 pb-1">
        <div>번호</div>
        <div>판매자</div>
        <div>수량</div>
        <div>입찰자</div>
        <div>입찰가</div>
        <div>단가</div>
        <div>마감가</div>
        <div>종료</div>
      </div>

      {/* Table rows */}
      {auctions.map((a) => {
        const d = p(a);
        const isMine = d.sellerId === myId;
        const curBid = d.currentBid ?? d.minPrice ?? 0;
        const amount = d.amount ?? 0;
        const bidRatio = d.currentBid && amount > 0 ? (d.currentBid / amount).toFixed(2) : "-";
        const seller = d.sellerName ?? (d.sellerId ? (genMap.get(d.sellerId)?.name ?? `#${d.sellerId}`) : "-");
        const bidder = d.currentBidderName ?? (d.currentBidderId ? (genMap.get(d.currentBidderId)?.name ?? `#${d.currentBidderId}`) : "-");

        return (
          <div key={a.id}>
            <div
              className={`grid grid-cols-8 gap-1 text-center text-xs py-1 border-b border-gray-800 cursor-pointer hover:bg-white/5 ${isMine ? "bg-cyan-900/20" : ""}`}
              onClick={() => setSelectedId(selectedId === a.id ? null : a.id)}
            >
              <div className="tabular-nums">{a.id}</div>
              <div className="truncate">{seller}</div>
              <div className="tabular-nums">{unitLabel} {amount.toLocaleString()}</div>
              <div className="truncate">{bidder}</div>
              <div className={`tabular-nums ${d.currentBid ? "" : "text-gray-500"}`}>
                {bidUnitLabel} {curBid.toLocaleString()}
              </div>
              <div className="tabular-nums">{bidRatio}</div>
              <div className="tabular-nums">{d.finishBidAmount ? `${bidUnitLabel} ${d.finishBidAmount.toLocaleString()}` : "-"}</div>
              <div className="tabular-nums">{d.endTime ? cutDateTime(d.endTime) : "-"}</div>
            </div>

            {/* Bid row when selected */}
            {selectedId === a.id && !isMine && myId != null && (
              <div className="flex items-center gap-2 py-1.5 px-2 bg-gray-800/50">
                <span className="text-xs text-muted-foreground">
                  {a.id}번 {unitLabel} {amount.toLocaleString()} 경매에 {bidUnitLabel}
                </span>
                <Input
                  type="number"
                  placeholder={`${curBid + 1} 이상`}
                  value={bidAmounts[a.id] ?? ""}
                  onChange={(e) => setBidAmounts((x) => ({ ...x, [a.id]: e.target.value }))}
                  className="h-7 w-28 text-xs"
                  min={d.minPrice ?? 0}
                  max={d.finishBidAmount}
                />
                <Button
                  size="sm"
                  onClick={() => onBid(a.id)}
                  disabled={bidding === a.id || !bidAmounts[a.id] || Number(bidAmounts[a.id]) <= 0}
                  className="h-7 text-xs"
                >
                  {bidding === a.id ? "..." : "입찰"}
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/* ── Generic Active auction row (fallback for non-table auctions) ── */
function AuctionRow({
  auction,
  myId,
  bidVal,
  onBidVal,
  onBid,
  isBidding,
  genMap,
  showItem,
}: {
  auction: Message;
  myId?: number;
  bidVal: string;
  onBidVal: (v: string) => void;
  onBid: () => void;
  isBidding: boolean;
  genMap: Map<number, General>;
  showItem?: boolean;
}) {
  const d = p(auction);
  const isMine = d.sellerId === myId;
  const curPrice = d.currentBid ?? d.minPrice ?? 0;
  const seller =
    d.sellerName ??
    (d.sellerId ? (genMap.get(d.sellerId)?.name ?? `#${d.sellerId}`) : "-");
  const bidder =
    d.currentBidderName ??
    (d.currentBidderId
      ? (genMap.get(d.currentBidderId)?.name ?? `#${d.currentBidderId}`)
      : "-");

  return (
    <div className="border border-gray-700 rounded p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        {showItem ? (
          <span className="text-amber-400 font-bold text-sm">
            {d.itemName ?? d.item}
          </span>
        ) : (
          <>
            <span className={`font-bold text-sm ${RESOURCE_COLORS[d.item ?? ""] ?? ""}`}>
              {RESOURCE_LABELS[d.item ?? ""] ?? d.item}
            </span>
            <span className="text-sm">{(d.amount ?? 0).toLocaleString()}</span>
          </>
        )}
        <Badge variant="secondary" className="text-[10px]">
          <Clock className="inline size-3 mr-0.5" />
          {remaining(d.endTime)}
        </Badge>
        {isMine && (
          <Badge variant="outline" className="text-[10px]">내 경매</Badge>
        )}
      </div>

      {showItem && d.itemStats && Object.keys(d.itemStats).length > 0 && (
        <div className="flex gap-3 text-xs text-muted-foreground">
          {Object.entries(d.itemStats).map(([k, v]) => (
            <span key={k}>
              {k}: <span className="text-foreground">+{v}</span>
            </span>
          ))}
        </div>
      )}

      <div className="flex items-center gap-4 text-xs flex-wrap">
        <span className="text-muted-foreground">
          판매자: <span className="text-foreground">{seller}</span>
        </span>
        <span className="text-muted-foreground">
          최고가: <span className="text-yellow-400 font-bold">{curPrice.toLocaleString()}</span>
        </span>
        {d.currentBidderId != null && (
          <span className="text-muted-foreground">
            입찰자: <span className="text-foreground">{bidder}</span>
          </span>
        )}
        <span className="text-muted-foreground">입찰수: {d.bidCount ?? 0}</span>
      </div>

      {!isMine && myId != null && (
        <div className="flex items-center gap-2">
          <Input
            type="number"
            placeholder={`${curPrice + 1} 이상`}
            value={bidVal}
            onChange={(e) => onBidVal(e.target.value)}
            className="h-8 w-32 text-xs"
          />
          <Button
            size="sm"
            onClick={onBid}
            disabled={isBidding || !bidVal || Number(bidVal) <= curPrice}
            className="h-8 text-xs"
          >
            {isBidding ? "입찰중..." : "입찰"}
          </Button>
        </div>
      )}
    </div>
  );
}
