# Claude Code — Project Workspace

Workspace folders for Claude Code in this project.

| Folder | Purpose |
|--------|---------|
| `commands/` | Slash commands (`.md` with YAML front matter), e.g. `/build`, `/test` |
| `agents/` | Subagent definitions (`.md`), specialized roles for Claude |
| `skills/` | Project skills (folders containing `SKILL.md`) |
| `hooks/` | Shell scripts invoked by Claude Code hooks (wired up in `settings.json`) |
| `plans/` | Plan files (`.md`) — task briefs, implementation plans |

## Git workflow (project convention)

- `main` is always stable and ready to run.
- **All work happens on a separate branch** — never commit directly to `main`.
  - Milestones: `milestone/<number>-<short-name>` (e.g. `milestone/2-game-logic`).
  - Other work: `feature/<name>`, `fix/<name>`, `docs/<name>`, `chore/<name>`.
- **Code and its tests live on the same branch**, committed together (atomic commits).
- **Code review is mandatory before merge** — a reviewer subagent must pass before a branch is merged into `main`.
- Commits are **atomic**: one logical step = one commit with a meaningful message.
- The project plan lives in `docs/tic-tac-toe-plan.md`.

## Where to put a plan

Task briefs and one-off implementation plans go in `plans/` as `.md` files, e.g. `plans/plan.md`. The project roadmap lives in `docs/tic-tac-toe-plan.md`.