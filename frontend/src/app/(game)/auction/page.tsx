"use client";

import { useEffect, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { Gavel } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { Message } from "@/types";
import { auctionApi } from "@/lib/gameApi";

export default function AuctionPage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const [auctions, setAuctions] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [bidAmounts, setBidAmounts] = useState<Record<number, string>>({});

  const loadAuctions = useCallback(async () => {
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
    loadAuctions();
  }, [loadAuctions]);

  const handleBid = async (auctionId: number) => {
    if (!myGeneral) return;
    const amount = parseInt(bidAmounts[auctionId] ?? "0");
    if (amount <= 0) return;
    try {
      await auctionApi.bid(auctionId, myGeneral.id, amount);
      await loadAuctions();
      setBidAmounts((prev) => ({ ...prev, [auctionId]: "" }));
    } catch {
      /* ignore */
    }
  };

  const resourceAuctions = auctions.filter((a) => a.messageType === "resource");
  const itemAuctions = auctions.filter((a) => a.messageType === "item");

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="max-w-3xl mx-auto space-y-4">
      <PageHeader icon={Gavel} title="경매" />

      <Tabs defaultValue="resource">
        <TabsList>
          <TabsTrigger value="resource">자원 경매</TabsTrigger>
          <TabsTrigger value="item">아이템 경매</TabsTrigger>
        </TabsList>

        <TabsContent value="resource">
          {resourceAuctions.length === 0 ? (
            <EmptyState icon={Gavel} title="진행 중인 자원 경매가 없습니다." />
          ) : (
            <div className="space-y-2">
              {resourceAuctions.map((a) => (
                <AuctionCard
                  key={a.id}
                  auction={a}
                  bidAmount={bidAmounts[a.id] ?? ""}
                  onBidChange={(val) =>
                    setBidAmounts((prev) => ({ ...prev, [a.id]: val }))
                  }
                  onBid={() => handleBid(a.id)}
                />
              ))}
            </div>
          )}
        </TabsContent>

        <TabsContent value="item">
          {itemAuctions.length === 0 ? (
            <EmptyState
              icon={Gavel}
              title="진행 중인 아이템 경매가 없습니다."
            />
          ) : (
            <div className="space-y-2">
              {itemAuctions.map((a) => (
                <AuctionCard
                  key={a.id}
                  auction={a}
                  bidAmount={bidAmounts[a.id] ?? ""}
                  onBidChange={(val) =>
                    setBidAmounts((prev) => ({ ...prev, [a.id]: val }))
                  }
                  onBid={() => handleBid(a.id)}
                />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}

function AuctionCard({
  auction,
  bidAmount,
  onBidChange,
  onBid,
}: {
  auction: Message;
  bidAmount: string;
  onBidChange: (val: string) => void;
  onBid: () => void;
}) {
  const item = auction.payload.item as string;
  const amount = auction.payload.amount as number;
  const currentBid = (auction.payload.currentBid as number) ?? 0;
  const state = auction.payload.state as string;

  return (
    <Card>
      <CardContent className="flex items-center justify-between gap-4">
        <div>
          <p className="font-medium text-sm">{item}</p>
          <p className="text-xs text-muted-foreground">수량: {amount}</p>
          <div className="flex gap-2 mt-1">
            <Badge variant="secondary">현재 입찰: {currentBid}</Badge>
            <Badge variant={state === "open" ? "default" : "outline"}>
              {state === "open" ? "진행중" : "종료"}
            </Badge>
          </div>
        </div>
        {state === "open" && (
          <div className="flex gap-1.5 items-center">
            <Input
              type="number"
              value={bidAmount}
              onChange={(e) => onBidChange(e.target.value)}
              placeholder="입찰가"
              className="w-24 text-xs"
            />
            <Button size="sm" onClick={onBid}>
              입찰
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
