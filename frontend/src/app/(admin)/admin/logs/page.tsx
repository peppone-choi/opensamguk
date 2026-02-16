"use client";

import { useState } from "react";
import { ScrollText, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { Message } from "@/types";

export default function AdminLogsPage() {
  const [generalId, setGeneralId] = useState("");
  const [logs, setLogs] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  const handleSearch = async () => {
    const id = Number(generalId);
    if (!id) {
      toast.error("장수 ID를 입력하세요.");
      return;
    }
    setLoading(true);
    try {
      const res = await adminApi.getGeneralLogs(id);
      setLogs(res.data);
      setSearched(true);
    } catch {
      toast.error("로그 조회 실패");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader icon={ScrollText} title="장수 로그" />
      <div className="flex items-center gap-2">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            type="number"
            placeholder="장수 ID"
            value={generalId}
            onChange={(e) => setGeneralId(e.target.value)}
            className="pl-8"
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
        </div>
        <Button
          onClick={handleSearch}
          className="bg-red-400 hover:bg-red-500 text-white"
        >
          조회
        </Button>
      </div>

      {loading && <LoadingState />}

      {!loading && searched && logs.length === 0 && (
        <p className="text-sm text-muted-foreground">로그가 없습니다.</p>
      )}

      {!loading && logs.length > 0 && (
        <div className="space-y-2">
          {logs.map((log) => (
            <Card key={log.id}>
              <CardContent className="text-sm space-y-1">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">
                    #{log.id} / {log.messageType}
                  </span>
                  <span className="text-muted-foreground">
                    {new Date(log.sentAt).toLocaleString("ko-KR")}
                  </span>
                </div>
                <pre className="text-xs whitespace-pre-wrap break-all">
                  {JSON.stringify(log.payload, null, 2)}
                </pre>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
