import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";

import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-1 whitespace-nowrap border text-sm font-bold transition-colors disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0 [&_svg]:shrink-0 outline-none",
  {
    variants: {
      variant: {
        default: "border-[#004621] bg-[#00582c] text-white hover:bg-[#006a33]",
        destructive:
          "border-[#5b0d0d] bg-[#7f1d1d] text-white hover:bg-[#942525]",
        outline: "border-[#666] bg-[#111] text-white hover:bg-[#1c1c1c]",
        secondary:
          "border-[#10164d] bg-[#141c65] text-white hover:bg-[#1a247f]",
        ghost: "border-[#333] bg-transparent text-[#d8d8d8] hover:bg-[#181818]",
        link: "border-transparent bg-transparent px-0 text-cyan-300 underline-offset-2 hover:underline",
      },
      size: {
        default: "h-8 px-3 py-1 has-[>svg]:px-2",
        xs: "h-6 gap-1 px-2 text-xs has-[>svg]:px-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-7 px-2.5 text-xs has-[>svg]:px-2",
        lg: "h-9 px-4",
        icon: "size-8",
        "icon-xs": "size-6 [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-7",
        "icon-lg": "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
  }) {
  const Comp = asChild ? Slot.Root : "button";

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}

export { Button, buttonVariants };
