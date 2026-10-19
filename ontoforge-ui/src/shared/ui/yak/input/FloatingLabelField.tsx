import type { ComponentProps, ReactNode } from "react";

import { cn } from "../cn";
import { FieldLabel } from "../field";

export type FloatingLabelFieldProps = Omit<ComponentProps<"div">, "children"> & {
  /** 与唯一子输入控件的 id 一致。 */
  htmlFor: string;
  label: ReactNode;
  required?: boolean;
  /** 一个 underlined Input 或 PasswordInput；错误提示放在本组合外。 */
  children: ReactNode;
};

/** 只组合标签与输入区域，不持有或复制输入值。 */
export function FloatingLabelField({
  htmlFor,
  label,
  required,
  children,
  className,
  ...props
}: FloatingLabelFieldProps) {
  return (
    <div {...props} className={cn("yak-floating-label-field", className)}>
      {children}
      <FieldLabel htmlFor={htmlFor} required={required} className="yak-floating-label-field__label">
        {label}
      </FieldLabel>
    </div>
  );
}
