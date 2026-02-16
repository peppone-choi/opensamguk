"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { diplomacyLetterApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Handshake, Send } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";

const STATE_LABELS: Record<string, string> = {
  war: "전쟁",
  ceasefire: "휴전",
  ceasefire_proposal: "종전제의",
  alliance: "동맹",
  nonaggression: "불가침",
  neutral: "중립",
};

const STATE_BADGE_VARIANT: Record<
  string,
  "destructive" | "default" | "secondary" | "outline"
> = {
  war: "destructive",
  ceasefire: "outline",
  ceasefire_proposal: "outline",
  alliance: "default",
  nonaggression: "secondary",
  neutral: "outline",
};

const LETTER_TYPES = [
  { value: "alliance", label: "동맹" },
  { value: "nonaggression", label: "불가침" },
  { value: "ceasefire", label: "종전" },
  { value: "war", label: "선전포고" },
];

export default function DiplomacyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { nations, diplomacy, loading, loadAll } = useGameStore();

  // Letter state
  const [letters, setLetters] = useState<Message[]>([]);
  const [lettersLoading, setLettersLoading] = useState(false);
  const [showSend, setShowSend] = useState(false);
  const [destNationId, setDestNationId] = useState("");
  const [letterType, setLetterType] = useState("alliance");
  const [letterContent, setLetterContent] = useState("");
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    setLettersLoading(true);
    diplomacyLetterApi
      .list(myGeneral.nationId)
      .then(({ data }) => setLetters(data))
      .catch(() => {})
      .finally(() => setLettersLoading(false));
  }, [myGeneral?.nationId]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const activeDiplomacy = useMemo(
    () => diplomacy.filter((d) => !d.isDead),
    [diplomacy],
  );

  const grouped = useMemo(() => {
    const groups: Record<string, typeof activeDiplomacy> = {};
    for (const d of activeDiplomacy) {
      const key = d.stateCode;
      if (!groups[key]) groups[key] = [];
      groups[key].push(d);
    }
    return groups;
  }, [activeDiplomacy]);

  const handleSendLetter = async () => {
    if (!currentWorld || !myGeneral?.nationId || !destNationId) return;
    setSending(true);
    try {
      await diplomacyLetterApi.send(myGeneral.nationId, {
        worldId: currentWorld.id,
        destNationId: Number(destNationId),
        type: letterType,
        content: letterContent || undefined,
      });
      setShowSend(false);
      setDestNationId("");
      setLetterContent("");
      const { data } = await diplomacyLetterApi.list(myGeneral.nationId);
      setLetters(data);
    } finally {
      setSending(false);
    }
  };

  const handleRespond = async (letterId: number, accept: boolean) => {
    await diplomacyLetterApi.respond(letterId, accept);
    if (myGeneral?.nationId) {
      const { data } = await diplomacyLetterApi.list(myGeneral.nationId);
      setLetters(data);
    }
  };

  const handleRollback = async (letterId: number) => {
    await diplomacyLetterApi.rollback(letterId);
    if (myGeneral?.nationId) {
      const { data } = await diplomacyLetterApi.list(myGeneral.nationId);
      setLetters(data);
    }
  };

  const handleDestroy = async (letterId: number) => {
    await diplomacyLetterApi.destroy(letterId);
    if (myGeneral?.nationId) {
      const { data } = await diplomacyLetterApi.list(myGeneral.nationId);
      setLetters(data);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  const sections = [
    { key: "war", label: "전쟁" },
    { key: "ceasefire", label: "휴전" },
    { key: "ceasefire_proposal", label: "종전제의" },
    { key: "alliance", label: "동맹" },
    { key: "nonaggression", label: "불가침" },
  ];

  const otherNations = nations.filter((n) => n.id !== myGeneral?.nationId);

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={Handshake} title="외교" />

      <Tabs defaultValue="status">
        <TabsList>
          <TabsTrigger value="status">외교 현황</TabsTrigger>
          <TabsTrigger value="letters">외교 서신</TabsTrigger>
        </TabsList>

        {/* Tab 1: Diplomacy status (existing) */}
        <TabsContent value="status" className="mt-4 space-y-4">
          {activeDiplomacy.length === 0 && (
            <EmptyState icon={Handshake} title="외교 관계가 없습니다." />
          )}

          {sections.map(({ key, label }) => {
            const items = grouped[key];
            if (!items || items.length === 0) return null;
            return (
              <Card key={key}>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    {label}
                    <Badge variant={STATE_BADGE_VARIANT[key] ?? "outline"}>
                      {items.length}
                    </Badge>
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  {items.map((d) => {
                    const src = nationMap.get(d.srcNationId);
                    const dest = nationMap.get(d.destNationId);
                    return (
                      <div
                        key={d.id}
                        className="flex items-center gap-3 rounded-lg border p-3"
                      >
                        <NationBadge name={src?.name} color={src?.color} />
                        <Badge variant={STATE_BADGE_VARIANT[key] ?? "outline"}>
                          {STATE_LABELS[key] ?? d.stateCode}
                        </Badge>
                        <NationBadge name={dest?.name} color={dest?.color} />
                        <span className="ml-auto text-xs text-muted-foreground">
                          {d.term}턴
                        </span>
                      </div>
                    );
                  })}
                </CardContent>
              </Card>
            );
          })}
        </TabsContent>

        {/* Tab 2: Diplomacy letters */}
        <TabsContent value="letters" className="mt-4 space-y-4">
          {myGeneral?.nationId && (
            <div className="flex justify-end">
              <Button
                onClick={() => setShowSend(!showSend)}
                variant={showSend ? "outline" : "default"}
                size="sm"
              >
                <Send className="size-4" />
                {showSend ? "취소" : "서신 보내기"}
              </Button>
            </div>
          )}

          {/* Send form */}
          {showSend && (
            <Card>
              <CardContent className="space-y-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    대상 국가
                  </label>
                  <select
                    value={destNationId}
                    onChange={(e) => setDestNationId(e.target.value)}
                    className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm"
                  >
                    <option value="">선택...</option>
                    {otherNations.map((n) => (
                      <option key={n.id} value={n.id}>
                        {n.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    유형
                  </label>
                  <select
                    value={letterType}
                    onChange={(e) => setLetterType(e.target.value)}
                    className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm"
                  >
                    {LETTER_TYPES.map((lt) => (
                      <option key={lt.value} value={lt.value}>
                        {lt.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1">
                    내용 (선택)
                  </label>
                  <Textarea
                    value={letterContent}
                    onChange={(e) => setLetterContent(e.target.value)}
                    placeholder="서신 내용..."
                    className="resize-none h-20"
                  />
                </div>
                <Button
                  onClick={handleSendLetter}
                  disabled={sending || !destNationId}
                >
                  {sending ? "전송 중..." : "전송"}
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Letter list */}
          {lettersLoading ? (
            <LoadingState />
          ) : letters.length === 0 ? (
            <EmptyState icon={Handshake} title="외교 서신이 없습니다." />
          ) : (
            letters.map((letter) => {
              const id = letter.id;
              const srcNation = nationMap.get(letter.srcId as number);
              const destNation = nationMap.get(letter.destId as number);
              const type = letter.messageType;
              const content = letter.payload.content as string | undefined;
              const state = letter.payload.state as string | undefined;

              return (
                <Card key={id}>
                  <CardContent className="space-y-2">
                    <div className="flex items-center gap-2">
                      <NationBadge
                        name={srcNation?.name}
                        color={srcNation?.color}
                      />
                      <span className="text-muted-foreground">→</span>
                      <NationBadge
                        name={destNation?.name}
                        color={destNation?.color}
                      />
                      <Badge variant="secondary">
                        {STATE_LABELS[type] ?? type}
                      </Badge>
                      {state && <Badge variant="outline">{state}</Badge>}
                    </div>
                    {content && <p className="text-sm">{content}</p>}
                    <div className="flex gap-2">
                      {state === "pending" &&
                        myGeneral?.nationId === (letter.destId as number) && (
                          <>
                            <Button
                              size="sm"
                              onClick={() => handleRespond(id, true)}
                            >
                              수락
                            </Button>
                            <Button
                              size="sm"
                              variant="destructive"
                              onClick={() => handleRespond(id, false)}
                            >
                              거절
                            </Button>
                          </>
                        )}
                      {state === "pending" &&
                        myGeneral?.nationId === (letter.srcId as number) && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleRollback(id)}
                          >
                            철회
                          </Button>
                        )}
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => handleDestroy(id)}
                      >
                        삭제
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              );
            })
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
