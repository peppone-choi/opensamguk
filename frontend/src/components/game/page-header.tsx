"use client";

import { usePathname, useRouter } from "next/navigation";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";

interface PageHeaderProps {
  icon?: LucideIcon;
  title: string;
  description?: string;
}

export function PageHeader({
  title,
  description,
}: PageHeaderProps) {
  const router = useRouter();
  const pathname = usePathname();

  const isMainPage = pathname === "/";

  return (
    <div className="space-y-1 legacy-page-wrap">
      <div
        className="legacy-bg0"
        style={{
          display: "grid",
          gridTemplateColumns: "90px 90px 1fr 90px 90px",
          minHeight: "32px",
          borderTop: "1px solid #666",
          borderBottom: "1px solid #666",
          alignItems: "stretch",
        }}
      >
        <Button
          variant="outline"
          size="sm"
          className="h-full border-0 border-r border-gray-600"
          onClick={() => {
            if (isMainPage) {
              router.push("/lobby");
              return;
            }
            router.push("/");
          }}
        >
          {isMainPage ? "로비로" : "돌아가기"}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="h-full border-0 border-r border-gray-600"
          onClick={() => window.location.reload()}
        >
          갱신
        </Button>
        <h2 className="m-0 text-center text-base font-bold leading-8">{title}</h2>
        <div className="border-l border-gray-600" />
        <div className="border-l border-gray-600" />
      </div>
      {description && (
        <p className="border border-gray-600 bg-[#111] px-2 py-1 text-xs text-gray-300">
          {description}
        </p>
      )}
    </div>
  );
}
