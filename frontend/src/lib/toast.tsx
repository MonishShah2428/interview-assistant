import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import { CircleAlert, CircleCheck, Info, X } from "lucide-react";
import clsx from "clsx";

type Tone = "info" | "success" | "error";

interface Toast {
  id: number;
  tone: Tone;
  message: string;
}

interface ToastApi {
  show: (message: string, tone?: Tone) => void;
  /** Screen-reader-only announcement, no visible toast. */
  announce: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const ICONS = { info: Info, success: CircleCheck, error: CircleAlert };

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [announcement, setAnnouncement] = useState("");
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((ts) => ts.filter((t) => t.id !== id)), []);

  const show = useCallback(
    (message: string, tone: Tone = "info") => {
      const id = nextId.current++;
      setToasts((ts) => [...ts.slice(-2), { id, tone, message }]);
      window.setTimeout(() => dismiss(id), tone === "error" ? 7000 : 4500);
    },
    [dismiss],
  );

  const announce = useCallback((message: string) => setAnnouncement(message), []);
  const value = useMemo(() => ({ show, announce }), [show, announce]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="sr-only" aria-live="polite">
        {announcement}
      </div>
      <div className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col items-end gap-2 sm:inset-x-auto sm:right-5">
        {toasts.map((t) => {
          const Icon = ICONS[t.tone];
          return (
            <div
              key={t.id}
              role={t.tone === "error" ? "alert" : "status"}
              className="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-md border border-line bg-surface px-4 py-3 text-sm shadow-lg shadow-black/25"
            >
              <Icon
                aria-hidden
                strokeWidth={1.75}
                className={clsx(
                  "mt-0.5 size-4 shrink-0",
                  t.tone === "error" && "text-failed",
                  t.tone === "success" && "text-ready",
                  t.tone === "info" && "text-working",
                )}
              />
              <p className="flex-1">{t.message}</p>
              <button
                type="button"
                onClick={() => dismiss(t.id)}
                aria-label="Dismiss"
                className="-m-1 cursor-pointer rounded p-1 text-muted hover:text-fg"
              >
                <X aria-hidden className="size-4" strokeWidth={1.75} />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside ToastProvider");
  return ctx;
}
