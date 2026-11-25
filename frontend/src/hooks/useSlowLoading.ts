import { useEffect, useState } from "react";

/** True after {@code isPending} stays true past {@code delayMs}, hinting a backend cold start. */
export function useSlowLoading(isPending: boolean, delayMs = 3000): boolean {
  const [elapsed, setElapsed] = useState(false);

  useEffect(() => {
    if (!isPending) return;
    const timer = window.setTimeout(() => setElapsed(true), delayMs);
    return () => {
      window.clearTimeout(timer);
      setElapsed(false);
    };
  }, [isPending, delayMs]);

  return isPending && elapsed;
}
