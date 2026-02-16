"use client";

import { ScrollArea } from "@/components/ui/scroll-area";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatLog } from "@/lib/formatLog";
import type { RecordEntry } from "@/types";

interface RecordZoneProps {
  generalRecords: RecordEntry[];
  globalRecords: RecordEntry[];
  historyRecords: RecordEntry[];
}

export function RecordZone({
  generalRecords,
  globalRecords,
  historyRecords,
}: RecordZoneProps) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
      <RecordColumn title="장수동향" records={generalRecords} />
      <RecordColumn title="개인기록" records={globalRecords} />
      <RecordColumn title="중원정세" records={historyRecords} />
    </div>
  );
}

function RecordColumn({
  title,
  records,
}: {
  title: string;
  records: RecordEntry[];
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-xs">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ScrollArea className="h-40">
          <div className="space-y-1">
            {records.length === 0 ? (
              <p className="text-xs text-muted-foreground">기록 없음</p>
            ) : (
              records.map((r) => (
                <div key={r.id} className="text-xs leading-relaxed">
                  <span className="text-muted-foreground mr-1">[{r.date}]</span>
                  {formatLog(r.message)}
                </div>
              ))
            )}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
