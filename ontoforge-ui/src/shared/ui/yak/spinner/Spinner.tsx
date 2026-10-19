import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "../cn";

const spinnerVariants = cva("inline-flex shrink-0 items-center justify-center", {
  variants: {
    size: {
      small: "size-3",
      medium: "size-3.5",
      large: "size-4",
      xlarge: "size-5",
    },
  },
  defaultVariants: { size: "medium" },
});

export type SpinnerProps = VariantProps<typeof spinnerVariants> & {
  className?: string;
  label?: string;
};

export function Spinner({ className, label = "Loading", size }: SpinnerProps) {
  return (
    <span
      role="status"
      aria-label={label}
      className={cn("text-[var(--yak-components-spinner)]", spinnerVariants({ size }), className)}
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 16 16"
        fill="none"
        className="size-full animate-spin [animation-duration:750ms] motion-reduce:animate-none"
      >
        <circle
          cx="8"
          cy="8"
          r="5.75"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeDasharray="24 12.2"
        />
      </svg>
      <span className="sr-only">{label}</span>
    </span>
  );
}
