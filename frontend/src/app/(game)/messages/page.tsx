"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { messageApi, diplomacyApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
import type { MailboxType, Message } from "@/types";
import { ChevronDown, Mail, PenLine, Reply, Send, Trash2 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";

type MailboxTab = "public" | "national" | "private" | "diplomacy";
type ComposeRecipientType = "public" | "general" | "nation";

export default function MessagesPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const { generals, nations, loadAll } = useGameStore();
  const [tab, setTab] = useState<MailboxTab>("public");
  const [messagesByTab, setMessagesByTab] = useState<
    Record<MailboxTab, Message[]>
  >({
    public: [],
    national: [],
    private: [],
    diplomacy: [],
  });
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [showCompose, setShowCompose] = useState(false);
  const [recipientType, setRecipientType] =
    useState<ComposeRecipientType>("general");
  const [mailboxType, setMailboxType] = useState<MailboxType>("PRIVATE");
  const [destGeneralId, setDestGeneralId] = useState("");
  const [destNationId, setDestNationId] = useState("");
  const [content, setContent] = useState("");
  const [sending, setSending] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [hasMoreByTab, setHasMoreByTab] = useState<Record<MailboxTab, boolean>>({
    public: true,
    national: true,
    private: true,
    diplomacy: true,
  });

  const canUseDiplomacy = (myGeneral?.officerLevel ?? 0) >= 4;

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const fetchMessages = useCallback(async () => {
    if (!currentWorld || !myGeneral) return;
    setRefreshing(true);
    try {
      const [publicRes, nationalRes, privateRes, diplomacyRes] =
        await Promise.all([
          messageApi.getByType("public", { worldId: currentWorld.id }),
          messageApi.getByType("national", { nationId: myGeneral.nationId }),
          messageApi.getByType("private", { generalId: myGeneral.id }),
          canUseDiplomacy
            ? messageApi.getByType("diplomacy", {
                nationId: myGeneral.nationId,
                officerLevel: myGeneral.officerLevel,
              })
            : Promise.resolve({ data: [] as Message[] }),
        ]);

      setMessagesByTab({
        public: publicRes.data,
        national: nationalRes.data,
        private: privateRes.data,
        diplomacy: diplomacyRes.data,
      });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [currentWorld, myGeneral, canUseDiplomacy]);

  useEffect(() => {
    if (!myGeneral || !currentWorld) return;
    fetchMessages();
  }, [myGeneral, currentWorld, fetchMessages]);

  const loadOlderMessages = useCallback(async () => {
    if (!currentWorld || !myGeneral) return;
    const currentInbox = messagesByTab[tab];
    if (currentInbox.length === 0) return;

    const oldestId = Math.min(...currentInbox.map((m) => m.id));
    setLoadingOlder(true);
    try {
      const typeParams = {
        public: { worldId: currentWorld.id },
        national: { nationId: myGeneral.nationId },
        private: { generalId: myGeneral.id },
        diplomacy: { nationId: myGeneral.nationId, officerLevel: myGeneral.officerLevel },
      }[tab];
      const res = await messageApi.getByType(tab, { ...typeParams, beforeId: oldestId, limit: 30 });
      if (res.data.length === 0) {
        setHasMoreByTab((prev) => ({ ...prev, [tab]: false }));
      } else {
        setMessagesByTab((prev) => ({
          ...prev,
          [tab]: [...prev[tab], ...res.data],
        }));
      }
    } finally {
      setLoadingOlder(false);
    }
  }, [currentWorld, myGeneral, tab, messagesByTab]);

  /** Set compose form to reply to a specific message sender */
  const handleReplyTo = useCallback((msg: Message) => {
    const isNationMsg = msg.mailboxType === "NATIONAL" || msg.mailboxType === "DIPLOMACY";
    if (isNationMsg && msg.srcId) {
      setRecipientType("nation");
      setMailboxType(msg.mailboxType === "DIPLOMACY" ? "DIPLOMACY" : "NATIONAL");
      setDestNationId(String(msg.srcId));
    } else if (msg.srcId) {
      setRecipientType("general");
      setMailboxType("PRIVATE");
      setDestGeneralId(String(msg.srcId));
    }
    setShowCompose(true);
  }, []);

  useEffect(() => {
    if (!currentWorld || !myGeneral) return;

    const unsubTurn = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/turn`,
      () => {
        fetchMessages();
      },
    );
    const unsubMessage = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/message`,
      () => {
        fetchMessages();
      },
    );

    return () => {
      unsubTurn();
      unsubMessage();
    };
  }, [currentWorld, myGeneral, fetchMessages]);

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const inbox = messagesByTab[tab];

  const unreadCountByTab = useMemo(() => {
    const countUnread = (msgs: Message[]) =>
      msgs.filter((m) => typeof m.meta.readAt !== "string").length;
    return {
      public: countUnread(messagesByTab.public),
      national: countUnread(messagesByTab.national),
      private: countUnread(messagesByTab.private),
      diplomacy: countUnread(messagesByTab.diplomacy),
    };
  }, [messagesByTab]);

  const handleMarkAsRead = async (id: number) => {
    try {
      await messageApi.markAsRead(id);
      const now = new Date().toISOString();
      setMessagesByTab((prev) => ({
        ...prev,
        [tab]: prev[tab].map((m) =>
          m.id === id ? { ...m, meta: { ...m.meta, readAt: now } } : m,
        ),
      }));
    } catch {
      /* ignore */
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await messageApi.delete(id);
      await fetchMessages();
    } catch {
      /* ignore */
    }
  };

  const handleDiplomacyResponse = async (messageId: number, action: string, accept: boolean) => {
    if (!currentWorld) return;
    try {
      await diplomacyApi.respond(currentWorld.id, messageId, action, accept);
      await fetchMessages();
    } catch {
      /* ignore */
    }
  };

  const handleSend = async () => {
    if (!currentWorld || !myGeneral || !content.trim()) return;

    const trimmedContent = content.trim();
    const isPublic = recipientType === "public";
    const isGeneral = recipientType === "general";

    if (isGeneral && !destGeneralId) return;
    if (!isPublic && !isGeneral && !destNationId) return;

    const mailboxCode =
      mailboxType === "PUBLIC"
        ? "board"
        : mailboxType === "NATIONAL"
          ? "national"
          : mailboxType === "DIPLOMACY"
            ? "diplomacy"
            : "personal";

    const srcId =
      mailboxType === "NATIONAL" || mailboxType === "DIPLOMACY"
        ? myGeneral.nationId
        : myGeneral.id;

    const targetId =
      mailboxType === "NATIONAL" || mailboxType === "DIPLOMACY"
        ? Number(destNationId)
        : isPublic
          ? null
          : Number(destGeneralId);

    setSending(true);
    try {
      await messageApi.send(currentWorld.id, srcId, targetId, trimmedContent, {
        mailboxCode,
        mailboxType,
        messageType: mailboxCode,
        officerLevel: myGeneral.officerLevel,
      });
      setContent("");
      setDestGeneralId("");
      setDestNationId("");
      setShowCompose(false);
      await fetchMessages();
    } finally {
      setSending(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <div className="flex items-center justify-between">
        <PageHeader icon={Mail} title="서신" />
        <Button
          onClick={() => setShowCompose(!showCompose)}
          variant={showCompose ? "outline" : "default"}
          size="sm"
        >
          <PenLine />
          {showCompose ? "취소" : "서신 작성"}
        </Button>
      </div>

      {/* Compose form */}
      {showCompose && (
        <Card className="border-amber-900/60 bg-zinc-950/70">
          <CardContent className="space-y-3">
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <label
                  htmlFor="recipient-type"
                  className="block text-xs text-amber-200/80 mb-1"
                >
                  수신 대상
                </label>
                <select
                  id="recipient-type"
                  value={recipientType}
                  onChange={(e) => {
                    const nextType = e.target.value as ComposeRecipientType;
                    setRecipientType(nextType);
                    if (nextType === "public") setMailboxType("PUBLIC");
                    if (nextType === "general") setMailboxType("PRIVATE");
                    if (nextType === "nation") {
                      setMailboxType(
                        canUseDiplomacy ? "DIPLOMACY" : "NATIONAL",
                      );
                    }
                  }}
                  className="h-9 w-full min-w-0 rounded-md border border-amber-900/60 bg-zinc-950 px-3 py-1 text-sm text-amber-100 shadow-xs outline-none focus-visible:border-amber-500"
                >
                  <option value="public">공개 (전체)</option>
                  <option value="general">장수</option>
                  <option value="nation">국가</option>
                </select>
              </div>
              <div>
                <label
                  htmlFor="mailbox-type"
                  className="block text-xs text-muted-foreground mb-1"
                >
                  서신 종류
                </label>
                <select
                  id="mailbox-type"
                  value={mailboxType}
                  onChange={(e) =>
                    setMailboxType(e.target.value as MailboxType)
                  }
                  className="h-9 w-full min-w-0 rounded-md border border-amber-900/60 bg-zinc-950 px-3 py-1 text-sm text-amber-100 shadow-xs outline-none focus-visible:border-amber-500"
                >
                  {recipientType === "public" && (
                    <option value="PUBLIC">공개 서신</option>
                  )}
                  {recipientType === "general" && (
                    <option value="PRIVATE">사적 서신</option>
                  )}
                  {recipientType === "nation" && (
                    <>
                      <option value="NATIONAL">국가 서신</option>
                      <option value="DIPLOMACY" disabled={!canUseDiplomacy}>
                        외교 서신 {!canUseDiplomacy ? "(관직 4 이상)" : ""}
                      </option>
                    </>
                  )}
                </select>
              </div>
            </div>
            {recipientType === "general" && (
              <div>
                <label
                  htmlFor="dest-general"
                  className="block text-xs text-muted-foreground mb-1"
                >
                  받는 장수
                </label>
                <select
                  id="dest-general"
                  value={destGeneralId}
                  onChange={(e) => setDestGeneralId(e.target.value)}
                  className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm"
                >
                  <option value="">선택...</option>
                  {Array.from(
                    generals
                      .filter((g) => g.id !== myGeneral?.id)
                      .reduce((map, g) => {
                        const nation = nationMap.get(g.nationId);
                        const key = nation?.name ?? "재야";
                        if (!map.has(key)) map.set(key, []);
                        map.get(key)!.push(g);
                        return map;
                      }, new Map<string, typeof generals>()),
                  ).map(([nationName, gens]) => (
                    <optgroup key={nationName} label={nationName}>
                      {gens.map((g) => (
                        <option key={g.id} value={g.id}>
                          {g.name}
                        </option>
                      ))}
                    </optgroup>
                  ))}
                </select>
              </div>
            )}
            {recipientType === "nation" && (
              <div>
                <label
                  htmlFor="dest-nation"
                  className="block text-xs text-muted-foreground mb-1"
                >
                  받는 국가
                </label>
                <select
                  id="dest-nation"
                  value={destNationId}
                  onChange={(e) => setDestNationId(e.target.value)}
                  className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm"
                >
                  <option value="">선택...</option>
                  {nations
                    .filter((n) => n.id !== myGeneral?.nationId)
                    .map((n) => (
                      <option key={n.id} value={n.id}>
                        {n.name}
                      </option>
                    ))}
                </select>
              </div>
            )}
            <div>
              <label
                htmlFor="message-content"
                className="block text-xs text-muted-foreground mb-1"
              >
                내용
              </label>
              <Textarea
                id="message-content"
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="서신 내용을 입력하세요..."
                className="resize-none h-20"
              />
            </div>
            <div className="flex justify-end">
              <Button
                onClick={handleSend}
                disabled={
                  sending ||
                  !content.trim() ||
                  (recipientType === "general" && !destGeneralId) ||
                  (recipientType === "nation" && !destNationId) ||
                  (mailboxType === "DIPLOMACY" && !canUseDiplomacy)
                }
                size="sm"
              >
                <Send />
                {sending ? "전송중..." : "전송"}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Tabs value={tab} onValueChange={(v) => setTab(v as MailboxTab)}>
        <TabsList className="grid grid-cols-4">
          <TabsTrigger value="public">
            공개 서신
            {unreadCountByTab.public > 0 && (
              <Badge className="ml-1 bg-amber-600 text-black hover:bg-amber-500">
                {unreadCountByTab.public}
              </Badge>
            )}
          </TabsTrigger>
          <TabsTrigger value="national">
            국가 서신
            {unreadCountByTab.national > 0 && (
              <Badge className="ml-1 bg-amber-600 text-black hover:bg-amber-500">
                {unreadCountByTab.national}
              </Badge>
            )}
          </TabsTrigger>
          <TabsTrigger value="private">
            사적 서신
            {unreadCountByTab.private > 0 && (
              <Badge className="ml-1 bg-amber-600 text-black hover:bg-amber-500">
                {unreadCountByTab.private}
              </Badge>
            )}
          </TabsTrigger>
          <TabsTrigger value="diplomacy" disabled={!canUseDiplomacy}>
            외교 서신
            {unreadCountByTab.diplomacy > 0 && (
              <Badge className="ml-1 bg-amber-600 text-black hover:bg-amber-500">
                {unreadCountByTab.diplomacy}
              </Badge>
            )}
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Inbox */}
      <div className="space-y-2">
        {!canUseDiplomacy && tab === "diplomacy" && (
          <div className="text-xs text-amber-300">
            외교 서신은 관직 4 이상만 열람할 수 있습니다.
          </div>
        )}
        {refreshing && (
          <div className="text-xs text-muted-foreground">
            서신을 갱신하고 있습니다...
          </div>
        )}
        {inbox.length === 0 ? (
          <EmptyState icon={Mail} title="서신이 없습니다." />
        ) : (
          [...inbox].sort((a, b) => new Date(b.sentAt).getTime() - new Date(a.sentAt).getTime()).map((m) => {
            const senderGeneral = m.srcId ? generalMap.get(m.srcId) : null;
            const senderNation = m.srcId ? nationMap.get(m.srcId) : null;
            const senderName =
              m.mailboxType === "NATIONAL" || m.mailboxType === "DIPLOMACY"
                ? senderNation?.name
                : senderGeneral?.name;
            return (
              <Card key={m.id} onClick={() => handleMarkAsRead(m.id)}>
                <CardContent className="space-y-1">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">
                      {senderName ??
                        (m.payload.sender as string) ??
                        `${m.mailboxType === "NATIONAL" || m.mailboxType === "DIPLOMACY" ? "국가" : "장수"}#${m.srcId ?? "시스템"}`}
                    </span>
                    <span>{new Date(m.sentAt).toLocaleString("ko-KR")}</span>
                    {typeof m.meta.readAt !== "string" && (
                      <Badge className="bg-amber-500 text-black">NEW</Badge>
                    )}
                    {m.srcId != null && m.mailboxType !== "PUBLIC" && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="ml-auto h-6 w-6 p-0"
                        title="답장"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleReplyTo(m);
                        }}
                      >
                        <Reply className="size-3.5" />
                      </Button>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      className={`h-6 w-6 p-0 ${m.srcId != null && m.mailboxType !== "PUBLIC" ? "" : "ml-auto"}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDelete(m.id);
                      }}
                    >
                      <Trash2 className="size-3.5" />
                    </Button>
                  </div>
                  <p className="text-sm">
                    {(m.payload.content as string) ?? JSON.stringify(m.payload)}
                  </p>
                  {/* Diplomacy action buttons (수락/거절) */}
                  {m.mailboxType === "DIPLOMACY" && typeof m.payload.action === "string" && (
                    <div className="flex gap-2 mt-2" onClick={(e) => e.stopPropagation()}>
                      <Button
                        size="sm"
                        variant="default"
                        className="bg-green-700 hover:bg-green-600 text-xs h-7"
                        onClick={() => handleDiplomacyResponse(m.id, m.payload.action as string, true)}
                      >
                        수락
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        className="text-xs h-7"
                        onClick={() => handleDiplomacyResponse(m.id, m.payload.action as string, false)}
                      >
                        거절
                      </Button>
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })
        )}
        {inbox.length > 0 && hasMoreByTab[tab] && (
          <Button
            variant="outline"
            size="sm"
            className="w-full"
            disabled={loadingOlder}
            onClick={loadOlderMessages}
          >
            <ChevronDown className="size-3.5 mr-1" />
            {loadingOlder ? "불러오는 중..." : "이전 메시지 불러오기"}
          </Button>
        )}
      </div>
    </div>
  );
}
