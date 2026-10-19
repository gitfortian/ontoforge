import type { Input as BaseInputNS } from "@base-ui/react/input";
import { Input as BaseInput } from "@base-ui/react/input";
import { cva, type VariantProps } from "class-variance-authority";
import { forwardRef } from "react";

import { cn } from "../cn";

export const inputVariants = cva(
  [
    "w-full appearance-none text-[var(--yak-components-input-text)] outline-none",
    "placeholder:text-[var(--yak-components-input-placeholder)]",
    "transition-[background-color,border-color,color] duration-150",
    "focus:border-[var(--yak-components-input-border-focus)]",
    "aria-[invalid=true]:border-[var(--yak-components-input-border-danger)]",
    "data-invalid:border-[var(--yak-components-input-border-danger)]",
    "disabled:cursor-not-allowed disabled:text-[var(--yak-components-input-text-disabled)]",
    "read-only:cursor-default",
    "motion-reduce:transition-none",
  ],
  {
    variants: {
      variant: {
        filled:
          "border-transparent bg-[var(--yak-components-input-bg)] hover:border-[var(--yak-components-input-border-hover)] hover:bg-[var(--yak-components-input-bg-hover)] disabled:border-transparent",
        outlined:
          "border-[var(--yak-components-input-border)] bg-[var(--yak-components-input-bg-focus)] hover:border-[var(--yak-components-input-border-focus)] hover:bg-[var(--yak-components-input-bg-focus)] disabled:border-[var(--yak-components-input-border)]",
        underlined:
          "rounded-none border-0 border-b border-[var(--yak-components-input-border)] bg-transparent px-0 hover:border-[var(--yak-components-input-border-focus)] aria-[invalid=true]:hover:border-[var(--yak-components-input-border-danger)] aria-[invalid=true]:focus:border-[var(--yak-components-input-border-danger)] data-invalid:hover:border-[var(--yak-components-input-border-danger)] data-invalid:focus:border-[var(--yak-components-input-border-danger)] disabled:border-[var(--yak-components-input-border)]",
      },
      size: {
        small: "h-7 text-[length:var(--yak-font-size-control-small)]",
        medium: "h-9 text-[length:var(--yak-font-size-control-medium)]",
        large: "h-10 text-[length:var(--yak-font-size-control-large)]",
      },
    },
    compoundVariants: [
      {
        variant: ["filled", "outlined"],
        className:
          "border focus:bg-[var(--yak-components-input-bg-focus)] disabled:bg-[var(--yak-components-input-bg-disabled)]",
      },
      {
        variant: ["filled", "outlined"],
        size: "small",
        className: "rounded-[var(--yak-radius-control-small)] px-2.5",
      },
      {
        variant: ["filled", "outlined"],
        size: "medium",
        className: "rounded-[var(--yak-radius-control-medium)] px-3",
      },
      {
        variant: ["filled", "outlined"],
        size: "large",
        className: "rounded-[var(--yak-radius-control-large)] px-3.5",
      },
    ],
    defaultVariants: {
      variant: "filled",
      size: "medium",
    },
  },
);

export type InputProps = Omit<BaseInputNS.Props, "className" | "size"> &
  VariantProps<typeof inputVariants> & {
    className?: string;
  };

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, size, variant, placeholder, ...props },
  ref,
) {
  return (
    <BaseInput
      {...props}
      ref={ref}
      data-yak-size={size ?? "medium"}
      placeholder={variant === "underlined" ? placeholder || " " : placeholder}
      className={cn(inputVariants({ size, variant }), className)}
    />
  );
});

Input.displayName = "Input";
