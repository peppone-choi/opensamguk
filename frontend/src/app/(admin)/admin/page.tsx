"use client";

import { useEffect, useState } from "react";
import { LayoutDashboard } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { AdminDashboard } from "@/types";

export default function AdminDashboardPage() {
  const [data, setData] = useState<AdminDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState("");
  const [turnTerm, setTurnTerm] = useState("");
  const [locked, setLocked] = useState(false);

  useEffect(() => {
    adminApi
      .getDashboard()
      .then((res) => {
        const d = res.data;
        setData(d);
        if (d.currentWorld) {
          setNotice((d.currentWorld.config?.notice as string) ?? "");
          setTurnTerm(String(d.currentWorld.config?.turnTerm ?? ""));
          setLocked(Boolean(d.currentWorld.config?.locked));
        }
      })
      .catch(() => {
        toast.error("해당 월드 관리자 권한이 없습니다.");
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const handleSave = async () => {
    try {
      await adminApi.updateSettings({
        notice,
        turnTerm: turnTerm ? Number(turnTerm) : undefined,
        locked,
      });
      toast.success("설정이 저장되었습니다.");
    } catch {
      toast.error("저장 실패");
    }
  };

  if (loading) return <LoadingState />;

  const world = data?.currentWorld;

  return (
    <div className="space-y-4">
      <PageHeader icon={LayoutDashboard} title="관리자 대시보드" />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <CardHeader>
            <CardTitle>월드 수</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">{data?.worldCount ?? 0}</span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>현재 시점</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">
              {world ? `${world.year}년 ${world.month}월` : "-"}
            </span>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>시나리오</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="text-2xl font-bold">
              {world?.scenarioCode ?? "-"}
            </span>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>게임 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">공지사항</label>
            <Input
              value={notice}
              onChange={(e) => setNotice(e.target.value)}
              placeholder="공지사항 입력"
            />
          </div>
          <div className="space-y-1">
            <label className="text-sm text-muted-foreground">
              턴 간격 (초)
            </label>
            <Input
              type="number"
              value={turnTerm}
              onChange={(e) => setTurnTerm(e.target.value)}
              placeholder="300"
            />
          </div>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={locked}
              onChange={(e) => setLocked(e.target.checked)}
              id="locked"
              className="accent-red-400"
            />
            <label htmlFor="locked" className="text-sm">
              서버 잠금
            </label>
          </div>
          <Button
            onClick={handleSave}
            className="bg-red-400 hover:bg-red-500 text-white"
          >
            저장
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
