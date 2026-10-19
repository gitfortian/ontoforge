import type { ReactNode } from "react";

import { Card } from "./Card";

export interface SectionCardProps {
  title: ReactNode;
  children: ReactNode;
}

export function SectionCard({ title, children }: SectionCardProps) {
  return (
    <Card className="p-4">
      <div className="mb-4 flex items-center gap-2">
        <span aria-hidden="true" className="h-4 w-[3px] shrink-0 bg-[var(--yak-color-primary)]" />
        <div className="min-w-0 text-sm font-semibold text-[var(--yak-components-panel-text)]">
          {title}
        </div>
      </div>
      {children}
    </Card>
  );
}
