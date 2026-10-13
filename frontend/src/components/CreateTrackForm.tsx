import { useId, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
import { useCreateTrack } from "../api/queries";
import { ApiError } from "../api/client";
import { Button } from "./ui";

const GOAL_MAX = 500;
const LEVEL_MAX = 50;
const LEVELS = ["intern", "junior", "mid", "senior", "staff"];

function createErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 502) return "The model couldn't break this goal down. Try again, or rephrase the goal.";
    if (error.status === 400) return "Check the goal and level: both are required, and the goal must be 500 characters or fewer.";
    return error.message;
  }
  return "Something went wrong creating the track. Try again.";
}

export function CreateTrackForm() {
  const navigate = useNavigate();
  const createTrack = useCreateTrack();
  const goalId = useId();
  const levelId = useId();
  const [goal, setGoal] = useState("");
  const [level, setLevel] = useState("mid");
  const [customLevel, setCustomLevel] = useState("");
  const [touched, setTouched] = useState(false);

  const effectiveLevel = (level === "other" ? customLevel : level).trim();
  const goalError = touched && !goal.trim() ? "Describe what you're preparing for." : null;
  const levelError = touched && !effectiveLevel ? "Name the level you're aiming for." : null;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (!goal.trim() || !effectiveLevel) return;
    createTrack.mutate(
      { goal: goal.trim(), level: effectiveLevel },
      { onSuccess: (track) => navigate(`/tracks/${track.id}`) },
    );
  };

  return (
    <form onSubmit={onSubmit} noValidate className="grid gap-6">
      <div className="grid gap-2">
        <label htmlFor={goalId} className="font-serif text-[28px] leading-tight font-semibold sm:text-[34px]">
          What are you preparing for?
        </label>
        <p id={`${goalId}-help`} className="text-muted">
          A role, an interview loop, or one area like system design. You'll get a map of topics to work through.
        </p>
        <textarea
          id={goalId}
          value={goal}
          onChange={(e) => setGoal(e.target.value.slice(0, GOAL_MAX))}
          onBlur={() => goal && setTouched(true)}
          rows={3}
          required
          aria-invalid={goalError ? true : undefined}
          aria-describedby={`${goalId}-help ${goalId}-count${goalError ? ` ${goalId}-error` : ""}`}
          placeholder="Backend SDE II interviews at a product company"
          className={clsx(
            "mt-2 w-full resize-y rounded-md border bg-surface px-4 py-3 text-base leading-relaxed placeholder:text-muted/70",
            goalError ? "border-failed" : "border-line focus:border-ring",
          )}
        />
        <div className="flex justify-between gap-4 text-sm">
          <span id={`${goalId}-error`} className="text-failed" role={goalError ? "alert" : undefined}>
            {goalError}
          </span>
          <span id={`${goalId}-count`} className="text-muted">
            {goal.length}/{GOAL_MAX}
          </span>
        </div>
      </div>

      <fieldset className="grid gap-3">
        <legend className="mb-2 font-medium">Target level</legend>
        <div className="flex flex-wrap gap-2">
          {[...LEVELS, "other"].map((l) => (
            <label
              key={l}
              className={clsx(
                "flex min-h-10 cursor-pointer items-center rounded-md border px-3.5 text-sm capitalize transition-colors duration-150",
                "has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-ring",
                level === l ? "border-ring/40 bg-mark text-on-mark" : "border-line bg-surface hover:bg-surface-2",
              )}
            >
              <input
                type="radio"
                name="level"
                value={l}
                checked={level === l}
                onChange={() => setLevel(l)}
                className="sr-only"
              />
              {l === "other" ? "Other" : l}
            </label>
          ))}
        </div>
        {level === "other" && (
          <div className="grid gap-1.5">
            <label htmlFor={levelId} className="text-sm text-muted">
              Describe the level
            </label>
            <input
              id={levelId}
              value={customLevel}
              onChange={(e) => setCustomLevel(e.target.value.slice(0, LEVEL_MAX))}
              placeholder="New grad, principal, career switcher…"
              aria-invalid={levelError ? true : undefined}
              className={clsx(
                "min-h-10 w-full max-w-sm rounded-md border bg-surface px-3 text-base placeholder:text-muted/70",
                levelError ? "border-failed" : "border-line focus:border-ring",
              )}
            />
          </div>
        )}
        {levelError && (
          <p role="alert" className="text-sm text-failed">
            {levelError}
          </p>
        )}
        <p className="text-sm text-muted">
          The level shapes everything: which topics count as core, how deep they go, and what gets searched.
        </p>
      </fieldset>

      <div className="flex flex-wrap items-center gap-4">
        <Button type="submit" variant="primary" busy={createTrack.isPending} className="min-h-11 px-5">
          {createTrack.isPending ? "Building your map" : "Build my map"}
        </Button>
        {createTrack.isPending && (
          <span className="text-sm text-muted" aria-live="polite">
            Breaking your goal into topics. This usually takes a few seconds.
          </span>
        )}
      </div>
      {createTrack.isError && (
        <p role="alert" className="text-sm text-failed">
          {createErrorMessage(createTrack.error)}
        </p>
      )}
    </form>
  );
}
