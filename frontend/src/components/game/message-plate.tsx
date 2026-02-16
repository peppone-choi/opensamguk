"use client";

import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { formatLog } from "@/lib/formatLog";
import { Trash2, Reply, Check, X } from "lucide-react";
import type { Message, General } from "@/types";

interface MessagePlateProps {
  message: Message;
  senderGeneral?: General | null;
  myGeneralId: number;
  onDelete?: (id: number) => void;
  onReply?: (srcId: number) => void;
  onDiplomacyRespond?: (id: number, accept: boolean) => void;
}

export function MessagePlate({
  message,
  senderGeneral,
  myGeneralId,
  onDelete,
  onReply,
  onDiplomacyRespond,
}: MessagePlateProps) {
  const isSent = message.srcId === myGeneralId;
  const content = (message.payload.content as string) ?? "";
  const isDiplomacy = message.messageType === "diplomacy";
  const responded = message.payload.responded as boolean | undefined;

  return (
    <Card className="text-sm">
      <CardContent className="flex gap-2 py-2">
        <GeneralPortrait
          picture={senderGeneral?.picture}
          name={senderGeneral?.name ?? "시스템"}
          size="sm"
        />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span className="font-medium text-xs">
              {senderGeneral?.name ?? `#${message.srcId ?? "시스템"}`}
            </span>
            <Badge
              variant={isSent ? "outline" : "secondary"}
              className="text-[10px]"
            >
              {isSent ? "발신" : "수신"}
            </Badge>
            {isDiplomacy && (
              <Badge variant="default" className="text-[10px]">
                외교
              </Badge>
            )}
            <span className="text-[10px] text-muted-foreground ml-auto">
              {new Date(message.sentAt).toLocaleString("ko-KR")}
            </span>
          </div>
          <p className="text-xs mt-1">{formatLog(content)}</p>

          {/* Actions */}
          <div className="flex gap-1 mt-1.5">
            {isDiplomacy && !isSent && !responded && onDiplomacyRespond && (
              <>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  className="text-green-400"
                  onClick={() => onDiplomacyRespond(message.id, true)}
                >
                  <Check className="size-3" />
                </Button>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  className="text-red-400"
                  onClick={() => onDiplomacyRespond(message.id, false)}
                >
                  <X className="size-3" />
                </Button>
              </>
            )}
            {onReply && !isSent && message.srcId && (
              <Button
                size="icon-sm"
                variant="ghost"
                onClick={() => onReply(message.srcId!)}
              >
                <Reply className="size-3" />
              </Button>
            )}
            {onDelete && (
              <Button
                size="icon-sm"
                variant="ghost"
                className="text-muted-foreground"
                onClick={() => onDelete(message.id)}
              >
                <Trash2 className="size-3" />
              </Button>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
