import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";

import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center justify-center gap-1 whitespace-nowrap border px-1.5 py-0 text-[11px] font-medium [&>svg]:size-3 [&>svg]:pointer-events-none",
  {
    variants: {
      variant: {
        default: "border-[#004621] bg-[#00582c] text-white",
        secondary: "border-[#10164d] bg-[#141c65] text-white",
        destructive: "border-[#5b0d0d] bg-[#7f1d1d] text-white",
        outline: "border-[#666] bg-[#111] text-white",
        ghost: "border-[#333] bg-transparent text-gray-300",
        link: "border-transparent bg-transparent px-0 text-cyan-300 underline-offset-2",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

function Badge({
  className,
  variant = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"span"> &
  VariantProps<typeof badgeVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "span";

  return (
    <Comp
      data-slot="badge"
      data-variant={variant}
      className={cn(badgeVariants({ variant }), className)}
      {...props}
    />
  );
}

export { Badge, badgeVariants };
