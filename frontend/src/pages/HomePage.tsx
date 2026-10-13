import { Link } from "react-router-dom";
import { Map } from "lucide-react";
import { useTracks } from "../api/queries";
import { CreateTrackForm } from "../components/CreateTrackForm";
import { ThemeToggle } from "../components/ThemeToggle";
import { Skeleton } from "../components/ui";
import { relativeTime } from "../lib/time";

export function HomePage() {
  const { data: tracks, error, isPending } = useTracks();

  return (
    <div className="min-h-dvh">
      <header className="mx-auto flex max-w-3xl items-center justify-between px-5 pt-5">
        <span className="flex items-center gap-2 font-medium">
          <Map aria-hidden strokeWidth={1.75} className="size-5 text-mark" />
          Prep map
        </span>
        <ThemeToggle />
      </header>

      <main id="main" className="mx-auto max-w-3xl px-5 pt-14 pb-24 sm:pt-20">
        <CreateTrackForm />

        <section aria-labelledby="tracks-heading" className="mt-20">
          <h2 id="tracks-heading" className="border-b border-line pb-3 font-semibold">
            Your tracks
          </h2>
          {isPending ? (
            <div className="grid gap-4 py-5">
              <Skeleton className="h-5 w-2/3" />
              <Skeleton className="h-5 w-1/2" />
            </div>
          ) : error ? (
            <p className="py-5 text-muted">{error.message}</p>
          ) : tracks.length === 0 ? (
            <p className="py-5 text-muted">No tracks yet. Describe your goal above to build your first map.</p>
          ) : (
            <ul className="divide-y divide-line">
              {tracks.map((t) => (
                <li key={t.id}>
                  <Link
                    to={`/tracks/${t.id}`}
                    className="group flex flex-col gap-1 py-4 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6"
                  >
                    <span className="font-serif text-lg font-semibold group-hover:text-mark">{t.goal}</span>
                    <span className="shrink-0 text-sm text-muted">
                      <span className="capitalize">{t.level}</span>, created {relativeTime(t.createdAt)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}
