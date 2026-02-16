"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
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

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const loadMessages = useCallback(async () => {
    try {
      const { data } = await messageApi.getMine(myGeneralId);
      setMessages(data);
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

  // Polling every 2.5 seconds
  useEffect(() => {
    const interval = setInterval(loadMessages, 2500);
    return () => clearInterval(interval);
  }, [loadMessages]);

  const filtered = useMemo(() => {
    switch (tab) {
      case "nation":
        return messages.filter((m) => m.mailboxCode === "nation");
      case "personal":
        return messages.filter(
          (m) => m.mailboxCode === "personal" || m.mailboxCode === "message",
        );
      case "diplomacy":
        return messages.filter((m) => m.messageType === "diplomacy");
      default:
        return messages;
    }
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
      setContent("");
      setDestId("");
      await loadMessages();
    } catch {
      /* ignore */
    } finally {
      setSending(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await messageApi.delete(id);
      await loadMessages();
    } catch {
      /* ignore */
    }
  };

  const handleReply = (srcId: number) => {
    setDestId(String(srcId));
  };

  const handleDiplomacyRespond = async (id: number, accept: boolean) => {
    try {
      await messageApi.respondDiplomacy(id, accept);
      await loadMessages();
    } catch {
      /* ignore */
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>서신</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {/* Compose */}
        <div className="flex gap-2">
          <select
            value={destId}
            onChange={(e) => setDestId(e.target.value)}
            className="h-8 flex-1 min-w-0 border border-gray-600 bg-[#111] px-2 text-xs"
          >
            <option value="">받는 장수...</option>
            {contacts
              .filter((c) => c.generalId !== myGeneralId)
              .map((c) => (
                <option key={c.generalId} value={c.generalId}>
                  {c.name} ({c.nationName})
                </option>
              ))}
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
                {filtered.length === 0 ? (
                  <p className="py-4 text-center text-xs text-gray-400">
                    서신 없음
                  </p>
                ) : (
                  filtered.map((m) => (
                    <MessagePlate
                      key={m.id}
                      message={m}
                      senderGeneral={
                        m.srcId ? (generalMap.get(m.srcId) ?? null) : null
                      }
                      myGeneralId={myGeneralId}
                      onDelete={handleDelete}
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
