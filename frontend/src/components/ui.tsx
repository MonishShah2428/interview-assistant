import type { ButtonHTMLAttributes, ReactNode } from "react";
import clsx from "clsx";
import { CircleAlert, CircleCheck, CircleDashed, LoaderCircle } from "lucide-react";
import type { EnrichmentStatus, ExpansionStatus } from "../api/types";

type Variant = "primary" | "secondary" | "ghost";

export function Button({
  variant = "secondary",
  busy = false,
  icon,
  children,
  className,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean; icon?: ReactNode }) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={clsx(
        "inline-flex min-h-9 cursor-pointer items-center justify-center gap-2 rounded-md px-3.5 text-sm font-medium transition-colors duration-150",
        "disabled:cursor-not-allowed disabled:opacity-50",
        variant === "primary" && "border border-ring/25 bg-mark text-on-mark hover:brightness-105",
        variant === "secondary" && "border border-line bg-surface text-fg hover:bg-surface-2",
        variant === "ghost" && "text-muted hover:bg-surface-2 hover:text-fg",
        className,
      )}
    >
      {busy ? <LoaderCircle aria-hidden className="size-4 animate-spin" strokeWidth={1.75} /> : icon}
      {children}
    </button>
  );
}

export type DisplayStatus = "idle" | "working" | "ready" | "failed";

/**
 * What one status dot means for a topic. Roots are containers that are never enriched, so for them
 * only expansion counts; for everything else enrichment is what the learner is waiting on.
 */
export function topicDisplayStatus(
  isRoot: boolean,
  expansion: ExpansionStatus,
  enrichment: EnrichmentStatus,
): DisplayStatus | null {
  if (expansion === "expanding") return "working";
  if (isRoot) return expansion === "failed" ? "failed" : null;
  if (enrichment === "enriching") return "working";
  if (enrichment === "ready") return "ready";
  if (enrichment === "failed") return "failed";
  return "idle";
}

const DOT_CLASS: Record<DisplayStatus, string> = {
  idle: "border border-idle",
  working: "bg-working pulse-dot",
  ready: "bg-ready",
  failed: "bg-failed",
};

export function StatusDot({ status, label }: { status: DisplayStatus; label: string }) {
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={clsx("inline-block size-2 shrink-0 rounded-full", DOT_CLASS[status])}
    />
  );
}

const ENRICHMENT_COPY: Record<EnrichmentStatus, { text: string; icon: typeof CircleCheck; tone: string }> = {
  pending: { text: "Waiting to search", icon: CircleDashed, tone: "text-muted" },
  enriching: { text: "Finding resources", icon: LoaderCircle, tone: "text-working" },
  ready: { text: "Resources ready", icon: CircleCheck, tone: "text-ready" },
  failed: { text: "Search failed", icon: CircleAlert, tone: "text-failed" },
};

const RETRYING_COPY = { text: "Retrying shortly", icon: CircleDashed, tone: "text-working" };

/** {@code retrying}: the last attempt failed but another is already queued, so it isn't final. */
export function EnrichmentLabel({ status, retrying = false }: { status: EnrichmentStatus; retrying?: boolean }) {
  const { text, icon: Icon, tone } = retrying ? RETRYING_COPY : ENRICHMENT_COPY[status];
  return (
    <span className={clsx("inline-flex items-center gap-1.5 text-sm font-medium", tone)}>
      <Icon aria-hidden strokeWidth={1.75} className={clsx("size-4", status === "enriching" && "animate-spin")} />
      {text}
    </span>
  );
}

export function enrichmentStatusText(status: EnrichmentStatus): string {
  return ENRICHMENT_COPY[status].text;
}

export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden className={clsx("skeleton block", className)} />;
}
