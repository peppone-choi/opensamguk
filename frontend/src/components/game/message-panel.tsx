"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ScrollArea } from "@/components/ui/scroll-area";
import { MessagePlate } from "@/components/game/message-plate";
import { messageApi } from "@/lib/gameApi";
import type { Message, General, ContactInfo } from "@/types";

interface MessagePanelProps {
  worldId: number;
  myGeneralId: number;
  generals: General[];
}

/* ── Recipient group helpers ── */
function groupContactsByNation(contacts: ContactInfo[], myGeneralId: number, lastContacts: Map<number, number>) {
  const filtered = contacts.filter((c) => c.generalId !== myGeneralId);

  // Build nation groups
  const nationGroups = new Map<string, { color: string; contacts: ContactInfo[] }>();
  for (const c of filtered) {
    const key = c.nationName || "재야";
    if (!nationGroups.has(key)) {
      nationGroups.set(key, { color: c.nationColor ?? "#888", contacts: [] });
    }
    nationGroups.get(key)!.contacts.push(c);
  }

  // Favorites (last_contact within 24h)
  const now = Date.now();
  const favorites = filtered
    .filter((c) => {
      const last = lastContacts.get(c.generalId);
      return last && now - last < 24 * 60 * 60 * 1000;
    })
    .sort((a, b) => (lastContacts.get(b.generalId) ?? 0) - (lastContacts.get(a.generalId) ?? 0));

  return { nationGroups, favorites };
}

export function MessagePanel({
  worldId,
  myGeneralId,
  generals,
}: MessagePanelProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [contacts, setContacts] = useState<ContactInfo[]>([]);
  const [destId, setDestId] = useState("");
  const [content, setContent] = useState("");
  const [sending, setSending] = useState(false);
  const [tab, setTab] = useState("all");
  const [recipientMode, setRecipientMode] = useState<"all" | "nation" | "favorites">("all");
  const lastSequenceRef = useRef<number | null>(null);
  const [lastContacts, setLastContacts] = useState<Map<number, number>>(new Map());

  // Load last_contact from localStorage
  useEffect(() => {
    try {
      const raw = localStorage.getItem(`opensam:lastContacts:${myGeneralId}`);
      if (raw) setLastContacts(new Map(Object.entries(JSON.parse(raw)).map(([k, v]) => [Number(k), Number(v)])));
    } catch { /* ignore */ }
  }, [myGeneralId]);

  const saveLastContact = useCallback((targetId: number) => {
    setLastContacts((prev) => {
      const next = new Map(prev);
      next.set(targetId, Date.now());
      // Keep only last 50 contacts
      const entries = Array.from(next.entries()).sort((a, b) => b[1] - a[1]).slice(0, 50);
      const trimmed = new Map(entries);
      try {
        localStorage.setItem(
          `opensam:lastContacts:${myGeneralId}`,
          JSON.stringify(Object.fromEntries(trimmed)),
        );
      } catch { /* ignore */ }
      return trimmed;
    });
  }, [myGeneralId]);

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const myNationId = useMemo(() => {
    const gen = generalMap.get(myGeneralId);
    return gen?.nationId ?? 0;
  }, [generalMap, myGeneralId]);

  const loadMessages = useCallback(async (incremental = false) => {
    try {
      const seq = incremental ? lastSequenceRef.current : null;
      const { data } = seq != null
        ? await messageApi.getMine(myGeneralId, seq)
        : await messageApi.getMine(myGeneralId);
      if (incremental && seq != null && data.length > 0) {
        setMessages((prev) => {
          const existing = new Set(prev.map((m) => m.id));
          const newMsgs = data.filter((m: Message) => !existing.has(m.id));
          return [...newMsgs, ...prev];
        });
      } else {
        setMessages(data);
      }
      // Update lastSequence from latest message
      if (data.length > 0) {
        const maxSeq = Math.max(...data.map((m: Message) => m.id));
        lastSequenceRef.current = maxSeq;
      }
    } catch {
      /* ignore */
    }
  }, [myGeneralId]);

  const loadContacts = useCallback(async () => {
    try {
      const { data } = await messageApi.getContacts(worldId);
      setContacts(data);
    } catch {
      /* ignore */
    }
  }, [worldId]);

  useEffect(() => {
    loadMessages();
    loadContacts();
  }, [loadMessages, loadContacts]);

  // Incremental polling every 2.5 seconds (only fetch new messages)
  useEffect(() => {
    const interval = setInterval(() => loadMessages(true), 2500);
    return () => clearInterval(interval);
  }, [loadMessages]);

  const filtered = useMemo(() => {
    let result: Message[];
    switch (tab) {
      case "nation":
        result = messages.filter((m) => m.mailboxCode === "nation");
        break;
      case "personal":
        result = messages.filter(
          (m) => m.mailboxCode === "personal" || m.mailboxCode === "message",
        );
        break;
      case "diplomacy":
        result = messages.filter((m) => m.messageType === "diplomacy");
        break;
      default:
        result = messages;
    }
    // Handle option.hide: filter out hidden messages
    return result.filter((m) => {
      const opts = m.payload?.option as Record<string, unknown> | undefined;
      if (opts?.hide) return false;
      return true;
    });
  }, [messages, tab]);

  const handleSend = async () => {
    if (!content.trim() || !destId) return;
    setSending(true);
    try {
      await messageApi.send(
        worldId,
        myGeneralId,
        Number(destId),
        content.trim(),
      );
      saveLastContact(Number(destId));
      setContent("");
      setDestId("");
      await loadMessages();
    } catch {
      /* ignore */
    } finally {
      setSending(false);
    }
  };

  // 5-minute time window for message delete
  const canDelete = useCallback((msg: Message) => {
    if (msg.srcId !== myGeneralId) return true; // received messages can always be deleted
    const sentAt = new Date(msg.sentAt).getTime();
    const now = Date.now();
    return now - sentAt < 5 * 60 * 1000; // 5 minutes
  }, [myGeneralId]);

  const handleDelete = async (id: number) => {
    const msg = messages.find((m) => m.id === id);
    if (msg && msg.srcId === myGeneralId && !canDelete(msg)) {
      return; // silently ignore - plate should not show button
    }
    try {
      await messageApi.delete(id);
      await loadMessages();
    } catch {
      /* ignore */
    }
  };

  const handleReply = (srcId: number) => {
    setDestId(String(srcId));
    saveLastContact(srcId);
  };

  const handleDiplomacyRespond = async (id: number, accept: boolean) => {
    try {
      await messageApi.respondDiplomacy(id, accept);
      await loadMessages();
    } catch {
      /* ignore */
    }
  };

  // Handle option.overwrite: replace matching existing messages
  const processedMessages = useMemo(() => {
    const overwriteMap = new Map<string, Message>();
    const result: Message[] = [];
    for (const m of filtered) {
      const opts = m.payload?.option as Record<string, unknown> | undefined;
      if (opts?.overwrite && typeof opts.overwrite === "string") {
        const key = opts.overwrite;
        if (overwriteMap.has(key)) {
          // Replace older message
          const idx = result.indexOf(overwriteMap.get(key)!);
          if (idx >= 0) result[idx] = m;
          overwriteMap.set(key, m);
          continue;
        }
        overwriteMap.set(key, m);
      }
      result.push(m);
    }
    return result;
  }, [filtered]);

  // Grouped recipients
  const { nationGroups, favorites } = useMemo(
    () => groupContactsByNation(contacts, myGeneralId, lastContacts),
    [contacts, myGeneralId, lastContacts],
  );

  const filteredContacts = useMemo(() => {
    switch (recipientMode) {
      case "favorites":
        return favorites;
      case "nation":
        return contacts.filter((c) => c.generalId !== myGeneralId && c.nationId === myNationId);
      default:
        return contacts.filter((c) => c.generalId !== myGeneralId);
    }
  }, [contacts, myGeneralId, myNationId, favorites, recipientMode]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>서신</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {/* Compose */}
        <div className="flex gap-1 items-center">
          <div className="flex gap-0.5">
            <button
              type="button"
              className={`text-[10px] px-1.5 py-0.5 rounded border ${recipientMode === "all" ? "border-cyan-500 bg-cyan-900/30 text-cyan-300" : "border-gray-600 text-gray-400"}`}
              onClick={() => setRecipientMode("all")}
            >
              전체
            </button>
            <button
              type="button"
              className={`text-[10px] px-1.5 py-0.5 rounded border ${recipientMode === "nation" ? "border-cyan-500 bg-cyan-900/30 text-cyan-300" : "border-gray-600 text-gray-400"}`}
              onClick={() => setRecipientMode("nation")}
            >
              아국
            </button>
            <button
              type="button"
              className={`text-[10px] px-1.5 py-0.5 rounded border ${recipientMode === "favorites" ? "border-cyan-500 bg-cyan-900/30 text-cyan-300" : "border-gray-600 text-gray-400"}`}
              onClick={() => setRecipientMode("favorites")}
            >
              즐겨찾기
            </button>
          </div>
          <select
            value={destId}
            onChange={(e) => setDestId(e.target.value)}
            className="h-8 flex-1 min-w-0 border border-gray-600 bg-[#111] px-2 text-xs"
          >
            <option value="">받는 장수...</option>
            {recipientMode === "all" ? (
              // Nation-grouped optgroups with color coding
              Array.from(nationGroups.entries()).map(([nationName, group]) => (
                <optgroup key={nationName} label={`── ${nationName} ──`} style={{ color: group.color }}>
                  {group.contacts.map((c) => (
                    <option key={c.generalId} value={c.generalId} style={{ color: group.color }}>
                      {c.name} ({c.nationName})
                    </option>
                  ))}
                </optgroup>
              ))
            ) : (
              filteredContacts.map((c) => (
                <option key={c.generalId} value={c.generalId}>
                  {c.name} ({c.nationName})
                </option>
              ))
            )}
          </select>
        </div>
        <div className="flex gap-2">
          <Textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="서신 내용..."
            className="h-16 resize-none text-xs"
          />
          <Button
            size="sm"
            variant="outline"
            onClick={handleSend}
            disabled={sending || !content.trim() || !destId}
            className="self-end"
          >
            전송
          </Button>
        </div>

        {/* Tabs */}
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList>
            <TabsTrigger value="all" className="text-xs">
              전체
            </TabsTrigger>
            <TabsTrigger value="personal" className="text-xs">
              개인
            </TabsTrigger>
            <TabsTrigger value="nation" className="text-xs">
              국가
            </TabsTrigger>
            <TabsTrigger value="diplomacy" className="text-xs">
              외교
            </TabsTrigger>
          </TabsList>
          <TabsContent value={tab}>
            <ScrollArea className="h-52">
              <div className="space-y-1.5">
                {processedMessages.length === 0 ? (
                  <p className="py-4 text-center text-xs text-gray-400">
                    서신 없음
                  </p>
                ) : (
                  processedMessages.map((m) => (
                    <MessagePlate
                      key={m.id}
                      message={m}
                      senderGeneral={
                        m.srcId ? (generalMap.get(m.srcId) ?? null) : null
                      }
                      myGeneralId={myGeneralId}
                      onDelete={canDelete(m) ? handleDelete : undefined}
                      onReply={handleReply}
                      onDiplomacyRespond={handleDiplomacyRespond}
                    />
                  ))
                )}
              </div>
            </ScrollArea>
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}
