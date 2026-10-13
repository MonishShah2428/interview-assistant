import { Moon, Sun } from "lucide-react";
import { useTheme } from "../lib/theme";

export function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const next = theme === "dark" ? "light" : "dark";
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
      className="flex size-9 cursor-pointer items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-fg"
    >
      {theme === "dark" ? (
        <Sun aria-hidden strokeWidth={1.75} className="size-4" />
      ) : (
        <Moon aria-hidden strokeWidth={1.75} className="size-4" />
      )}
    </button>
  );
}
