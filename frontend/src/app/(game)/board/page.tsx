"use client";

import { useEffect, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { messageApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { MessageSquare, Send, Lock } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";

export default function BoardPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const [messages, setMessages] = useState<Message[]>([]);
  const [secretMessages, setSecretMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [content, setContent] = useState("");
  const [secretContent, setSecretContent] = useState("");
  const [posting, setPosting] = useState(false);

  useEffect(() => {
    if (currentWorld && !myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  const fetchMessages = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await messageApi.getBoard(currentWorld.id);
      setMessages(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  const fetchSecretMessages = useCallback(async () => {
    if (!currentWorld || !myGeneral || myGeneral.officerLevel < 2) return;
    try {
      const { data } = await messageApi.getSecretBoard(
        currentWorld.id,
        myGeneral.nationId,
      );
      setSecretMessages(data);
    } catch {
      /* ignore */
    }
  }, [currentWorld, myGeneral]);

  useEffect(() => {
    fetchMessages();
  }, [fetchMessages]);

  useEffect(() => {
    fetchSecretMessages();
  }, [fetchSecretMessages]);

  const handlePost = async () => {
    if (!currentWorld || !myGeneral || !content.trim()) return;
    setPosting(true);
    try {
      await messageApi.postBoard(currentWorld.id, myGeneral.id, content.trim());
      setContent("");
      await fetchMessages();
    } finally {
      setPosting(false);
    }
  };

  const handleSecretPost = async () => {
    if (!currentWorld || !myGeneral || !secretContent.trim()) return;
    setPosting(true);
    try {
      await messageApi.postSecretBoard(
        currentWorld.id,
        myGeneral.id,
        myGeneral.nationId,
        secretContent.trim(),
      );
      setSecretContent("");
      await fetchSecretMessages();
    } finally {
      setPosting(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  const showSecret = myGeneral && myGeneral.officerLevel >= 2;

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={MessageSquare} title="게시판" />

      <Tabs defaultValue="public">
        <TabsList>
          <TabsTrigger value="public">공개 게시판</TabsTrigger>
          {showSecret && (
            <TabsTrigger value="secret">
              <Lock className="h-3 w-3 mr-1" />
              기밀실
            </TabsTrigger>
          )}
        </TabsList>

        <TabsContent value="public" className="space-y-4">
          <Card>
            <CardContent className="space-y-3">
              <Textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="글을 작성하세요..."
                className="resize-none h-20"
              />
              <div className="flex justify-end">
                <Button
                  onClick={handlePost}
                  disabled={posting || !content.trim()}
                  size="sm"
                >
                  <Send />
                  {posting ? "등록중..." : "등록"}
                </Button>
              </div>
            </CardContent>
          </Card>

          <div className="space-y-2">
            {messages.length === 0 ? (
              <EmptyState icon={MessageSquare} title="게시글이 없습니다." />
            ) : (
              messages.map((m) => (
                <Card key={m.id}>
                  <CardContent className="space-y-1">
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <span className="font-medium text-foreground">
                        {(m.payload.sender as string) ??
                          `장수#${m.srcId ?? "?"}`}
                      </span>
                      <span>{new Date(m.sentAt).toLocaleString("ko-KR")}</span>
                    </div>
                    <p className="text-sm">
                      {(m.payload.content as string) ??
                        (m.payload.message as string) ??
                        JSON.stringify(m.payload)}
                    </p>
                  </CardContent>
                </Card>
              ))
            )}
          </div>
        </TabsContent>

        {showSecret && (
          <TabsContent value="secret" className="space-y-4">
            <Card>
              <CardContent className="space-y-3">
                <Textarea
                  value={secretContent}
                  onChange={(e) => setSecretContent(e.target.value)}
                  placeholder="기밀 내용을 작성하세요... (관직자만 열람 가능)"
                  className="resize-none h-20"
                />
                <div className="flex justify-end">
                  <Button
                    onClick={handleSecretPost}
                    disabled={posting || !secretContent.trim()}
                    size="sm"
                  >
                    <Lock className="h-3 w-3" />
                    {posting ? "등록중..." : "등록"}
                  </Button>
                </div>
              </CardContent>
            </Card>

            <div className="space-y-2">
              {secretMessages.length === 0 ? (
                <EmptyState icon={Lock} title="기밀 게시글이 없습니다." />
              ) : (
                secretMessages.map((m) => (
                  <Card key={m.id}>
                    <CardContent className="space-y-1">
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        <span className="font-medium text-foreground">
                          {(m.payload.sender as string) ??
                            `장수#${m.srcId ?? "?"}`}
                        </span>
                        <span>
                          {new Date(m.sentAt).toLocaleString("ko-KR")}
                        </span>
                      </div>
                      <p className="text-sm">
                        {(m.payload.content as string) ??
                          JSON.stringify(m.payload)}
                      </p>
                    </CardContent>
                  </Card>
                ))
              )}
            </div>
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}
