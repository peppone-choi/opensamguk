"use client";

import { useState } from "react";
import Image from "next/image";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { User } from "lucide-react";
import { getPortraitUrl } from "@/lib/image";

const sizes = {
  sm: 32,
  md: 48,
  lg: 80,
} as const;

interface GeneralPortraitProps {
  picture?: string | null;
  name: string;
  size?: keyof typeof sizes;
  className?: string;
}

export function GeneralPortrait({
  picture,
  name,
  size = "sm",
  className,
}: GeneralPortraitProps) {
  const px = sizes[size];
  const [error, setError] = useState(false);
  const src = error ? getPortraitUrl(null) : getPortraitUrl(picture);

  if (picture && !error) {
    return (
      <Avatar className={className} style={{ width: px, height: px }}>
        <Image
          src={src}
          alt={name}
          width={px}
          height={px}
          className="object-cover"
          onError={() => setError(true)}
        />
        <AvatarFallback>
          <User className="size-4 text-muted-foreground" />
        </AvatarFallback>
      </Avatar>
    );
  }

  return (
    <Avatar className={className} style={{ width: px, height: px }}>
      <AvatarFallback>
        <User className="size-4 text-muted-foreground" />
      </AvatarFallback>
    </Avatar>
  );
}
