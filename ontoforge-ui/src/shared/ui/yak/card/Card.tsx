import type { HTMLAttributes, ReactNode } from "react";

import { cn } from "../cn";

export type CardProps = HTMLAttributes<HTMLDivElement>;

export function Card({ className, ...props }: CardProps) {
  return (
    <div
      {...props}
      className={cn(
        "overflow-hidden rounded-[var(--yak-radius-control-medium)] border border-[var(--yak-components-panel-border)] bg-[var(--yak-components-panel-bg)] text-[var(--yak-components-panel-text)]",
        className,
      )}
    />
  );
}

export interface CardHeaderProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  title?: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
}

export function CardHeader({
  children,
  className,
  description,
  extra,
  title,
  ...props
}: CardHeaderProps) {
  return (
    <div
      {...props}
      className={cn(
        "flex min-h-12 items-start justify-between gap-4 border-b border-[var(--yak-components-control-border)] px-4 py-3",
        className,
      )}
    >
      <div className="min-w-0">
        {title ? <div className="text-sm font-semibold">{title}</div> : null}
        {description ? (
          <div className="mt-1 text-xs leading-5 text-[var(--yak-components-muted-text)]">
            {description}
          </div>
        ) : null}
        {children}
      </div>
      {extra ? <div className="shrink-0">{extra}</div> : null}
    </div>
  );
}

export type CardContentProps = HTMLAttributes<HTMLDivElement>;

export function CardContent({ className, ...props }: CardContentProps) {
  return <div {...props} className={cn("p-4", className)} />;
}
