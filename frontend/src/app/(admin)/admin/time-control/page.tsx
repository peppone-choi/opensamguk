"use client";

import { useEffect, useState } from "react";
import { Clock } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";

export default function AdminTimeControlPage() {
  const [loading, setLoading] = useState(true);
  const [year, setYear] = useState("");
  const [month, setMonth] = useState("");
  const [locked, setLocked] = useState(false);

  useEffect(() => {
    adminApi.getDashboard().then((res) => {
      const w = res.data.currentWorld;
      if (w) {
        setYear(String(w.year));
        setMonth(String(w.month));
        setLocked(Boolean(w.config?.locked));
      }
      setLoading(false);
    });
  }, []);

  const handleSubmit = async () => {
    try {
      await adminApi.timeControl({
        year: year ? Number(year) : undefined,
        month: month ? Number(month) : undefined,
        locked,
      });
      toast.success("시간 설정이 변경되었습니다.");
    } catch {
      toast.error("변경 실패");
    }
  };

  if (loading) return <LoadingState />;

  return (
    <div className="space-y-4">
      <PageHeader icon={Clock} title="시간 제어" />
      <Card>
        <CardHeader>
          <CardTitle>게임 시간</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">년</label>
              <Input
                type="number"
                value={year}
                onChange={(e) => setYear(e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">월</label>
              <Input
                type="number"
                value={month}
                onChange={(e) => setMonth(e.target.value)}
                min={1}
                max={12}
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={locked}
              onChange={(e) => setLocked(e.target.checked)}
              id="lock"
              className="accent-red-400"
            />
            <label htmlFor="lock" className="text-sm">
              서버 잠금
            </label>
          </div>
          <Button
            onClick={handleSubmit}
            className="bg-red-400 hover:bg-red-500 text-white"
          >
            적용
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
