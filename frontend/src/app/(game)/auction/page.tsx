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

/* ── payload shape (stored in Message.payload) ── */
interface AuctionPayload {
  type?: string;
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
  status?: string;
  itemName?: string;
  itemStats?: Record<string, number>;
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

function isActive(payload: AuctionPayload): boolean {
  if (payload.status === "completed" || payload.status === "cancelled")
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

  // create-form state
  const [showCreate, setShowCreate] = useState(false);
  const [createType, setCreateType] = useState("resource");
  const [createItem, setCreateItem] = useState("gold");
  const [createAmount, setCreateAmount] = useState("");
  const [createMinPrice, setCreateMinPrice] = useState("");
  const [creating, setCreating] = useState(false);

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

  // poll every 5 s
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
    const minPrice = Number(createMinPrice);
    if (!amount || !minPrice) return;
    setCreating(true);
    try {
      await auctionApi.create(currentWorld.id, {
        type: createType,
        sellerId: myGeneral.id,
        item: createItem,
        amount,
        minPrice,
      });
      setShowCreate(false);
      setCreateAmount("");
      setCreateMinPrice("");
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
  const itemAuctions = auctions.filter((a) => p(a).type === "item");

  const activeResource = resourceAuctions.filter((a) => isActive(p(a)));
  const doneResource = resourceAuctions.filter((a) => !isActive(p(a)));
  const activeItem = itemAuctions.filter((a) => isActive(p(a)));
  const doneItem = itemAuctions.filter((a) => !isActive(p(a)));

  const mySelling = auctions.filter(
    (a) => p(a).sellerId === myGeneral?.id && isActive(p(a)),
  );
  const myBidding = auctions.filter(
    (a) =>
      p(a).currentBidderId === myGeneral?.id &&
      p(a).sellerId !== myGeneral?.id &&
      isActive(p(a)),
  );

  return (
    <div className="space-y-0 max-w-4xl mx-auto">
      <PageHeader icon={Gavel} title="경매장" />

      <Tabs defaultValue="resource" className="legacy-page-wrap">
        <TabsList className="w-full justify-start border-b border-gray-600">
          <TabsTrigger value="resource">
            <TrendingUp className="size-3.5 mr-1" />
            자원 경매
          </TabsTrigger>
          <TabsTrigger value="item">
            <Package className="size-3.5 mr-1" />
            아이템 경매
          </TabsTrigger>
        </TabsList>

        {/* ═══ Tab 1: Resource ═══ */}
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
                            {RESOURCE_LABELS[d.item ?? ""] ??
                              d.itemName ??
                              d.item}
                          </span>
                          <span>{(d.amount ?? 0).toLocaleString()}</span>
                          <span className="ml-auto text-muted-foreground">
                            현재가:{" "}
                            {(d.currentBid ?? d.minPrice ?? 0).toLocaleString()}
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
                            {RESOURCE_LABELS[d.item ?? ""] ??
                              d.itemName ??
                              d.item}
                          </span>
                          <span>
                            내 입찰: {(d.currentBid ?? 0).toLocaleString()}
                          </span>
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

          {/* create form */}
          {showCreate && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">경매 등록</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    분류
                  </label>
                  <select
                    value={createType}
                    onChange={(e) => setCreateType(e.target.value)}
                    className="h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                  >
                    <option value="resource">자원</option>
                    <option value="item">아이템</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    품목
                  </label>
                  {createType === "resource" ? (
                    <select
                      value={createItem}
                      onChange={(e) => setCreateItem(e.target.value)}
                      className="h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
                    >
                      <option value="gold">금</option>
                      <option value="rice">쌀</option>
                      <option value="crew">병사</option>
                    </select>
                  ) : (
                    <Input
                      value={createItem}
                      onChange={(e) => setCreateItem(e.target.value)}
                      placeholder="아이템 코드"
                    />
                  )}
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    수량
                  </label>
                  <Input
                    type="number"
                    value={createAmount}
                    onChange={(e) => setCreateAmount(e.target.value)}
                    placeholder="0"
                  />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    최소 입찰가
                  </label>
                  <Input
                    type="number"
                    value={createMinPrice}
                    onChange={(e) => setCreateMinPrice(e.target.value)}
                    placeholder="0"
                  />
                </div>
                <Button
                  onClick={handleCreate}
                  disabled={creating || !createAmount || !createMinPrice}
                >
                  {creating ? "등록 중..." : "등록"}
                </Button>
              </CardContent>
            </Card>
          )}

          {/* active resource */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">진행중 자원 경매</CardTitle>
            </CardHeader>
            <CardContent>
              {activeResource.length === 0 ? (
                <EmptyState
                  icon={Gavel}
                  title="진행중인 자원 경매가 없습니다."
                />
              ) : (
                <div className="space-y-2">
                  {activeResource.map((a) => (
                    <AuctionRow
                      key={a.id}
                      auction={a}
                      myId={myGeneral?.id}
                      bidVal={bidAmounts[a.id] ?? ""}
                      onBidVal={(v) =>
                        setBidAmounts((x) => ({ ...x, [a.id]: v }))
                      }
                      onBid={() => handleBid(a.id)}
                      isBidding={bidding === a.id}
                      genMap={genMap}
                    />
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* completed resource */}
          {doneResource.length > 0 && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">종료된 자원 경매</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-1">
                  {doneResource.slice(0, 20).map((a) => (
                    <CompletedRow key={a.id} auction={a} genMap={genMap} />
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* ═══ Tab 2: Item ═══ */}
        <TabsContent value="item" className="mt-4 space-y-4 px-2">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">진행중 아이템 경매</CardTitle>
            </CardHeader>
            <CardContent>
              {activeItem.length === 0 ? (
                <EmptyState
                  icon={Package}
                  title="진행중인 아이템 경매가 없습니다."
                />
              ) : (
                <div className="space-y-2">
                  {activeItem.map((a) => (
                    <AuctionRow
                      key={a.id}
                      auction={a}
                      myId={myGeneral?.id}
                      bidVal={bidAmounts[a.id] ?? ""}
                      onBidVal={(v) =>
                        setBidAmounts((x) => ({ ...x, [a.id]: v }))
                      }
                      onBid={() => handleBid(a.id)}
                      isBidding={bidding === a.id}
                      genMap={genMap}
                      showItem
                    />
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {doneItem.length > 0 && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">종료된 아이템 경매</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-1">
                  {doneItem.slice(0, 20).map((a) => (
                    <CompletedRow
                      key={a.id}
                      auction={a}
                      genMap={genMap}
                      isItem
                    />
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ── Active auction row ── */
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
            <span
              className={`font-bold text-sm ${RESOURCE_COLORS[d.item ?? ""] ?? ""}`}
            >
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
          <Badge variant="outline" className="text-[10px]">
            내 경매
          </Badge>
        )}
      </div>

      {/* item stat bonuses */}
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
          최고가:{" "}
          <span className="text-yellow-400 font-bold">
            {curPrice.toLocaleString()}
          </span>
        </span>
        {d.currentBidderId != null && (
          <span className="text-muted-foreground">
            입찰자: <span className="text-foreground">{bidder}</span>
          </span>
        )}
        <span className="text-muted-foreground">입찰수: {d.bidCount ?? 0}</span>
      </div>

      {/* bid form */}
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

/* ── Completed auction row ── */
function CompletedRow({
  auction,
  genMap,
  isItem,
}: {
  auction: Message;
  genMap: Map<number, General>;
  isItem?: boolean;
}) {
  const d = p(auction);
  const winner =
    d.currentBidderName ??
    (d.currentBidderId
      ? (genMap.get(d.currentBidderId)?.name ?? `#${d.currentBidderId}`)
      : "유찰");

  return (
    <div className="flex items-center gap-2 text-xs border border-gray-800 rounded px-2 py-1.5 opacity-70">
      <span
        className={
          isItem ? "text-amber-400" : (RESOURCE_COLORS[d.item ?? ""] ?? "")
        }
      >
        {isItem
          ? (d.itemName ?? d.item)
          : (RESOURCE_LABELS[d.item ?? ""] ?? d.item)}
      </span>
      {!isItem && <span>{(d.amount ?? 0).toLocaleString()}</span>}
      <span className="text-muted-foreground">
        낙찰가: {(d.currentBid ?? d.minPrice ?? 0).toLocaleString()}
      </span>
      <span className="ml-auto text-muted-foreground">{winner}</span>
      <Badge variant="outline" className="text-[10px]">
        종료
      </Badge>
    </div>
  );
}
