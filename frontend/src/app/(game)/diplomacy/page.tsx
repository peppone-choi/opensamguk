"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { diplomacyLetterApi, historyApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
import type { Message, Nation, Diplomacy } from "@/types";
import { Handshake, Send, Globe, History, ArrowRight } from "lucide-react";
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

const STATE_COLORS: Record<string, string> = {
  war: "#dc2626",
  alliance: "#16a34a",
  nonaggression: "#2563eb",
  ceasefire: "#ca8a04",
  ceasefire_proposal: "#ca8a04",
  neutral: "#555",
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
  const { nations, diplomacy, generals, cities, loading, loadAll } =
    useGameStore();

  // Letter state
  const [letters, setLetters] = useState<Message[]>([]);
  const [lettersLoading, setLettersLoading] = useState(false);
  const [showSend, setShowSend] = useState(false);
  const [destNationId, setDestNationId] = useState("");
  const [letterType, setLetterType] = useState("alliance");
  const [letterContent, setLetterContent] = useState("");
  const [sending, setSending] = useState(false);

  // History state
  const [historyRecords, setHistoryRecords] = useState<Message[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const loadLetters = useCallback(() => {
    if (!myGeneral?.nationId) return;
    setLettersLoading(true);
    diplomacyLetterApi
      .list(myGeneral.nationId)
      .then(({ data }) => setLetters(data))
      .catch(() => {})
      .finally(() => setLettersLoading(false));
  }, [myGeneral?.nationId]);

  useEffect(() => {
    loadLetters();
  }, [loadLetters]);

  // Load history records
  useEffect(() => {
    if (!currentWorld) return;
    setHistoryLoading(true);
    historyApi
      .getWorldHistory(currentWorld.id)
      .then(({ data }) => setHistoryRecords(data))
      .catch(() => {})
      .finally(() => setHistoryLoading(false));
  }, [currentWorld]);

  // Auto-refresh on diplomacy events via WebSocket
  useEffect(() => {
    if (!currentWorld) return;
    return subscribeWebSocket(
      `/topic/world/${currentWorld.id}/diplomacy`,
      () => {
        loadAll(currentWorld.id);
        loadLetters();
      },
    );
  }, [currentWorld, loadAll, loadLetters]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const activeDiplomacy = useMemo(
    () => diplomacy.filter((d) => !d.isDead),
    [diplomacy],
  );

  // Build NxN diplomacy lookup: key = "srcId-destId" → stateCode
  const diplomacyLookup = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of activeDiplomacy) {
      map.set(`${d.srcNationId}-${d.destNationId}`, d.stateCode);
      map.set(`${d.destNationId}-${d.srcNationId}`, d.stateCode);
    }
    return map;
  }, [activeDiplomacy]);

  // Group diplomacy by state for Tab 1
  const grouped = useMemo(() => {
    const groups: Record<string, Diplomacy[]> = {};
    for (const d of activeDiplomacy) {
      const key = d.stateCode;
      if (!groups[key]) groups[key] = [];
      groups[key].push(d);
    }
    return groups;
  }, [activeDiplomacy]);

  // Nation stats for power comparison
  const nationStats = useMemo(() => {
    return nations.map((n) => {
      const nationGenerals = generals.filter((g) => g.nationId === n.id);
      const nationCities = cities.filter((c) => c.nationId === n.id);
      const totalPop = nationCities.reduce((sum, c) => sum + c.pop, 0);
      const totalCrew = nationGenerals.reduce((sum, g) => sum + g.crew, 0);
      return {
        nation: n,
        genCount: nationGenerals.length,
        cityCount: nationCities.length,
        totalPop,
        totalCrew,
      };
    });
  }, [nations, generals, cities]);

  // Filter diplomacy-related history
  const diplomacyHistory = useMemo(() => {
    return historyRecords
      .filter((r) => {
        const msg = (r.payload?.content as string) ?? r.payload?.message;
        if (typeof msg !== "string") return false;
        return (
          msg.includes("동맹") ||
          msg.includes("불가침") ||
          msg.includes("선전포고") ||
          msg.includes("종전") ||
          msg.includes("휴전") ||
          msg.includes("외교") ||
          msg.includes("전쟁")
        );
      })
      .slice(0, 50);
  }, [historyRecords]);

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
  const canSendLetter =
    myGeneral && myGeneral.officerLevel >= 5 && myGeneral.nationId > 0;

  return (
    <div className="space-y-0 max-w-4xl mx-auto">
      <PageHeader icon={Handshake} title="외교" />

      <Tabs defaultValue="letters" className="legacy-page-wrap">
        <TabsList className="w-full justify-start border-b border-gray-600">
          <TabsTrigger value="letters">
            <Handshake className="size-3.5 mr-1" />
            외교부
          </TabsTrigger>
          <TabsTrigger value="global">
            <Globe className="size-3.5 mr-1" />
            중원정보
          </TabsTrigger>
          <TabsTrigger value="history">
            <History className="size-3.5 mr-1" />
            외교 기록
          </TabsTrigger>
        </TabsList>

        {/* Tab 1: 외교부 — Letters + Nation Diplomacy Status */}
        <TabsContent value="letters" className="mt-4 space-y-4 px-2">
          {/* My nation's diplomacy status summary */}
          {myGeneral?.nationId && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">외교 현황</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {activeDiplomacy.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    현재 외교 관계가 없습니다.
                  </p>
                ) : (
                  sections.map(({ key, label }) => {
                    const items = grouped[key];
                    if (!items || items.length === 0) return null;
                    return (
                      <div key={key} className="space-y-1">
                        <div className="flex items-center gap-2">
                          <Badge
                            variant={STATE_BADGE_VARIANT[key] ?? "outline"}
                          >
                            {label}
                          </Badge>
                          <span className="text-xs text-muted-foreground">
                            {items.length}건
                          </span>
                        </div>
                        {items.map((d) => {
                          const src = nationMap.get(d.srcNationId);
                          const dest = nationMap.get(d.destNationId);
                          return (
                            <div
                              key={d.id}
                              className="flex items-center gap-2 rounded border border-gray-700 px-2 py-1.5 text-sm"
                            >
                              <NationBadge
                                name={src?.name}
                                color={src?.color}
                              />
                              <ArrowRight className="size-3 text-muted-foreground" />
                              <NationBadge
                                name={dest?.name}
                                color={dest?.color}
                              />
                              <span className="ml-auto text-xs text-muted-foreground">
                                {d.term}턴
                              </span>
                            </div>
                          );
                        })}
                      </div>
                    );
                  })
                )}
              </CardContent>
            </Card>
          )}

          {/* Send button */}
          {canSendLetter && (
            <div className="flex justify-end">
              <Button
                onClick={() => setShowSend(!showSend)}
                variant={showSend ? "outline" : "default"}
                size="sm"
              >
                <Send className="size-4 mr-1" />
                {showSend ? "취소" : "서신 보내기"}
              </Button>
            </div>
          )}

          {/* Send form */}
          {showSend && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">외교 서신 작성</CardTitle>
              </CardHeader>
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
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">외교 서신</CardTitle>
            </CardHeader>
            <CardContent>
              {lettersLoading ? (
                <LoadingState />
              ) : letters.length === 0 ? (
                <EmptyState icon={Handshake} title="외교 서신이 없습니다." />
              ) : (
                <div className="space-y-2">
                  {letters.map((letter) => {
                    const id = letter.id;
                    const srcNation = nationMap.get(letter.srcId as number);
                    const destNation = nationMap.get(letter.destId as number);
                    const type = letter.messageType;
                    const content = letter.payload.content as
                      | string
                      | undefined;
                    const state = letter.payload.state as string | undefined;
                    const isOutgoing =
                      myGeneral?.nationId === (letter.srcId as number);

                    return (
                      <div
                        key={id}
                        className="rounded border border-gray-700 p-3 space-y-2"
                      >
                        <div className="flex items-center gap-2 flex-wrap">
                          <Badge variant="outline" className="text-xs">
                            {isOutgoing ? "발신" : "수신"}
                          </Badge>
                          <NationBadge
                            name={srcNation?.name}
                            color={srcNation?.color}
                          />
                          <ArrowRight className="size-3 text-muted-foreground" />
                          <NationBadge
                            name={destNation?.name}
                            color={destNation?.color}
                          />
                          <Badge variant="secondary">
                            {STATE_LABELS[type] ?? type}
                          </Badge>
                          {state && (
                            <Badge
                              variant={
                                state === "pending"
                                  ? "outline"
                                  : state === "accepted"
                                    ? "default"
                                    : "destructive"
                              }
                            >
                              {state === "pending"
                                ? "대기"
                                : state === "accepted"
                                  ? "수락"
                                  : state === "rejected"
                                    ? "거절"
                                    : state}
                            </Badge>
                          )}
                        </div>
                        {content && (
                          <p className="text-sm text-muted-foreground">
                            {content}
                          </p>
                        )}
                        <div className="flex gap-2">
                          {state === "pending" &&
                            myGeneral?.nationId ===
                              (letter.destId as number) && (
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
                            myGeneral?.nationId ===
                              (letter.srcId as number) && (
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
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Tab 2: 중원정보 — Global Diplomacy Matrix + Nation Power */}
        <TabsContent value="global" className="mt-4 space-y-4 px-2">
          {/* NxN Diplomacy Matrix */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">외교 관계도</CardTitle>
            </CardHeader>
            <CardContent>
              <DiplomacyMatrix
                nations={nations}
                diplomacyLookup={diplomacyLookup}
              />
            </CardContent>
          </Card>

          {/* Nation Power Comparison */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">국력 비교</CardTitle>
            </CardHeader>
            <CardContent>
              {nationStats.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  국가가 없습니다.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-gray-700">
                        <th className="text-left py-1.5 px-2">국가</th>
                        <th className="text-right py-1.5 px-2">장수</th>
                        <th className="text-right py-1.5 px-2">도시</th>
                        <th className="text-right py-1.5 px-2">인구</th>
                        <th className="text-right py-1.5 px-2">병력</th>
                        <th className="text-right py-1.5 px-2">기술</th>
                        <th className="text-right py-1.5 px-2">국력</th>
                      </tr>
                    </thead>
                    <tbody>
                      {nationStats
                        .sort((a, b) => b.nation.power - a.nation.power)
                        .map(
                          ({
                            nation: n,
                            genCount,
                            cityCount,
                            totalPop,
                            totalCrew,
                          }) => (
                            <tr
                              key={n.id}
                              className="border-b border-gray-800 hover:bg-gray-900/50"
                            >
                              <td className="py-1.5 px-2">
                                <NationBadge name={n.name} color={n.color} />
                              </td>
                              <td className="text-right py-1.5 px-2">
                                {genCount}
                              </td>
                              <td className="text-right py-1.5 px-2">
                                {cityCount}
                              </td>
                              <td className="text-right py-1.5 px-2">
                                {totalPop.toLocaleString()}
                              </td>
                              <td className="text-right py-1.5 px-2">
                                {totalCrew.toLocaleString()}
                              </td>
                              <td className="text-right py-1.5 px-2">
                                {n.tech}
                              </td>
                              <td className="text-right py-1.5 px-2 font-bold">
                                {n.power.toLocaleString()}
                              </td>
                            </tr>
                          ),
                        )}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Tab 3: 외교 기록 — History */}
        <TabsContent value="history" className="mt-4 space-y-4 px-2">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">외교 기록</CardTitle>
            </CardHeader>
            <CardContent>
              {historyLoading ? (
                <LoadingState />
              ) : diplomacyHistory.length === 0 ? (
                <EmptyState icon={History} title="외교 관련 기록이 없습니다." />
              ) : (
                <div className="space-y-1">
                  {diplomacyHistory.map((record) => {
                    const msg =
                      (record.payload?.content as string) ??
                      (record.payload?.message as string) ??
                      "";
                    const date = record.sentAt ?? record.validUntil ?? "";
                    return (
                      <div
                        key={record.id}
                        className="flex items-start gap-3 rounded border border-gray-800 px-3 py-2"
                      >
                        <span className="shrink-0 text-xs text-muted-foreground mt-0.5 w-24">
                          {date ? formatDate(date) : "-"}
                        </span>
                        <span className="text-sm">{msg}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}

/** NxN diplomacy relationship matrix */
function DiplomacyMatrix({
  nations,
  diplomacyLookup,
}: {
  nations: Nation[];
  diplomacyLookup: Map<string, string>;
}) {
  if (nations.length === 0)
    return <p className="text-xs text-muted-foreground">국가가 없습니다.</p>;

  return (
    <div className="overflow-x-auto">
      <table className="text-xs border-collapse">
        <thead>
          <tr>
            <th className="p-1" />
            {nations.map((n) => (
              <th
                key={n.id}
                className="p-1 text-center font-normal"
                style={{ color: n.color || undefined }}
              >
                <span className="[writing-mode:vertical-lr] whitespace-nowrap">
                  {n.name}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {nations.map((row) => (
            <tr key={row.id}>
              <td
                className="p-1 pr-2 text-right whitespace-nowrap font-bold"
                style={{ color: row.color || undefined }}
              >
                {row.name}
              </td>
              {nations.map((col) => {
                if (row.id === col.id) {
                  return (
                    <td
                      key={col.id}
                      className="p-1 text-center"
                      style={{ backgroundColor: "#222" }}
                    >
                      -
                    </td>
                  );
                }
                const state = diplomacyLookup.get(`${row.id}-${col.id}`);
                const bg = state ? (STATE_COLORS[state] ?? "#555") : "#1a1a1a";
                const label = state ? (STATE_LABELS[state] ?? state) : "";
                return (
                  <td
                    key={col.id}
                    className="p-1 text-center border border-gray-800"
                    style={{
                      backgroundColor: bg + "33",
                      color: bg,
                    }}
                    title={`${row.name} → ${col.name}: ${label || "중립"}`}
                  >
                    {label ? (
                      <span className="font-bold">{label.charAt(0)}</span>
                    ) : (
                      <span className="text-gray-600">-</span>
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {/* Legend */}
      <div className="flex flex-wrap gap-3 mt-3 text-xs">
        {Object.entries(STATE_LABELS).map(([code, label]) => (
          <span key={code} className="flex items-center gap-1">
            <span
              className="inline-block size-2.5 rounded-sm"
              style={{ backgroundColor: STATE_COLORS[code] ?? "#555" }}
            />
            {label}
          </span>
        ))}
      </div>
    </div>
  );
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
  } catch {
    return iso;
  }
}
