"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { messageApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Mail, PenLine, Send, Trash2 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";

export default function MessagesPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const { generals, loadAll } = useGameStore();
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCompose, setShowCompose] = useState(false);
  const [destId, setDestId] = useState("");
  const [content, setContent] = useState("");
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const fetchMessages = async () => {
    if (!myGeneral) return;
    try {
      const { data } = await messageApi.getMine(myGeneral.id);
      setMessages(data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!myGeneral) return;
    let active = true;
    messageApi
      .getMine(myGeneral.id)
      .then(({ data }) => {
        if (active) setMessages(data);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [myGeneral]);

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const inbox = useMemo(
    () =>
      messages
        .filter(
          (m) =>
            m.mailboxCode === "personal" ||
            m.mailboxCode === "message" ||
            (m.destId != null && m.destId === myGeneral?.id),
        )
        .sort(
          (a, b) => new Date(b.sentAt).getTime() - new Date(a.sentAt).getTime(),
        ),
    [messages, myGeneral],
  );

  const handleMarkAsRead = async (id: number) => {
    try {
      await messageApi.markAsRead(id);
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

  const handleSend = async () => {
    if (!currentWorld || !content.trim() || !destId) return;
    setSending(true);
    try {
      await messageApi.send(
        currentWorld.id,
        myGeneral!.id,
        Number(destId),
        content.trim(),
      );
      setContent("");
      setDestId("");
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
        <Card>
          <CardContent className="space-y-3">
            <div>
              <label className="block text-xs text-muted-foreground mb-1">
                받는 장수
              </label>
              <select
                value={destId}
                onChange={(e) => setDestId(e.target.value)}
                className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm"
              >
                <option value="">선택...</option>
                {generals
                  .filter((g) => g.id !== myGeneral?.id)
                  .map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name}
                    </option>
                  ))}
              </select>
            </div>
            <div>
              <label className="block text-xs text-muted-foreground mb-1">
                내용
              </label>
              <Textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="서신 내용을 입력하세요..."
                className="resize-none h-20"
              />
            </div>
            <div className="flex justify-end">
              <Button
                onClick={handleSend}
                disabled={sending || !content.trim() || !destId}
                size="sm"
              >
                <Send />
                {sending ? "전송중..." : "전송"}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Inbox */}
      <div className="space-y-2">
        {inbox.length === 0 ? (
          <EmptyState icon={Mail} title="받은 서신이 없습니다." />
        ) : (
          inbox.map((m) => {
            const sender = m.srcId ? generalMap.get(m.srcId) : null;
            return (
              <Card key={m.id} onClick={() => handleMarkAsRead(m.id)}>
                <CardContent className="space-y-1">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">
                      {sender?.name ??
                        (m.payload.sender as string) ??
                        `장수#${m.srcId ?? "시스템"}`}
                    </span>
                    <span>{new Date(m.sentAt).toLocaleString("ko-KR")}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="ml-auto h-6 w-6 p-0"
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
                </CardContent>
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
}
