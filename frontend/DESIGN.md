# Prep map — design system

The single source of truth for how the frontend looks. Tokens live in `src/index.css`;
components never use raw hex.

## Concept: a night study desk

The subject is studying for technical interviews: you work through a syllabus and
highlight what matters. The UI borrows from that, not from SaaS dashboards.

- **Ink-blue ground** (not neutral near-black) with warm off-white text, like a lamp-lit page.
- **Highlighter yellow is the one bold element.** It marks the selected topic in the map
  (a marker-swipe behind the label) and the concepts on a topic. Nothing else uses it.
- **Textbook headings.** Topic titles and the track goal are set in IBM Plex Serif; everything
  else is IBM Plex Sans. IBM Plex Mono appears only for the search agent's literal query
  strings, because they are verbatim machine input.
- **Ruled lists, not card grids.** Resources are rows separated by hairlines, grouped by lane.

Deliberately avoided: one-accent-on-black, all-caps eyebrows, mono metadata labels,
identical rounded cards with soft shadows, decorative gradients, entrance animations.

> Origin: generated with the ui-ux-pro-max skill ("developer tool, professional") and then
> revised with the frontend-design skill's self-critique, which flagged the slate+green and
> mono-label defaults.

## Color tokens

| Token | Dark (default) | Light | Use |
|---|---|---|---|
| `bg` | `#12162A` | `#F3F4F8` | App ground |
| `surface` | `#191E36` | `#FFFFFF` | Rail, panels |
| `surface-2` | `#232946` | `#E9EBF2` | Hover, inputs |
| `line` | `#2E3556` | `#D3D6E2` | Hairlines, borders |
| `fg` | `#ECE7DA` | `#171B30` | Primary text |
| `muted` | `#A6A9C0` | `#50556E` | Secondary text |
| `mark` / `on-mark` | `#F4D35E` / `#1B1A10` | `#F7DC6F` / `#1B1A10` | Highlighter |
| `ready` | `#6FCF97` | `#17703F` | Enriched |
| `working` | `#93A5FF` | `#4054C8` | Expanding / enriching (pulses) |
| `failed` | `#FF8A80` | `#C2362B` | Failed |
| `idle` | `#7D82A3` | `#7A7F98` | Waiting / not started |
| `lane-read` | `#86B7EA` | `#2862A8` | Read lane (+ BookOpen icon) |
| `lane-watch` | `#F0909F` | `#B23A55` | Watch lane (+ PlayCircle icon) |
| `lane-practice` | `#72D4AA` | `#126B4A` | Practice lane (+ Code2 icon) |
| `ring` | `#F4D35E` | `#1B1F3A` | Focus rings, active tab underline (yellow is invisible on light ground) |

Status and lane are never conveyed by color alone: each has an icon and a text label.

## Type

- Scale (px): 12, 14, 16, 20, 28. Body 15/1.55 in the panel, 14/1.45 in the rail.
- Plex Sans 400 body, 500 labels, 600 emphasis. Plex Serif 600 for topic and track titles.
- Sentence case everywhere. Tabular figures for counts.
- Measure: panel content capped at ~72ch.

## Layout

```
┌ header: Prep map / track goal (serif) / level / live indicator / theme ─────┐
├ map rail 300px ─────┬ topic panel (left-aligned, max ~72ch) ─────────────────┤
│ ▾ System Design     │ System Design / Caching            (path)             │
│   ● Caching ▓▓▓▓▓▓  │ Caching                     (serif 28)                 │
│   ◌ Sharding        │ Ready · searched 4 min ago   [Expand] [Find more]      │
│ ▸ Data Structures   │ Concepts: ▓LRU eviction▓ ▓Write-through▓ …             │
│                     │ [Read 3] [Watch 2] [Practice 1] [All 6]                │
│                     │ ─ title · domain · type ─────────────────────────────  │
└─────────────────────┴────────────────────────────────────────────────────────┘
```
Below 1024px the rail becomes a slide-over drawer opened from the header.

## Motion

150–220ms ease-out, only in response to an action (expand chevron, drawer, tab change).
The only ambient motion is the pulsing "working" dot. All of it stops under
`prefers-reduced-motion`.

## Accessibility

WAI-ARIA tree for the map (arrow keys, Home/End, Enter), visible focus rings in `ring`,
`aria-live` announcements for status changes and toasts, skip link to the topic panel,
44px minimum hit area on touch layouts.
