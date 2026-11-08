# Agent instructions — cough-to-pause

Repo conventions for coding agents. `README.md` describes the product; this file describes how to
work here without wasting turns. It is provider-agnostic: it assumes a shell and a git/GitHub CLI,
not any particular assistant.

## Waiting on slow work

- **Wait inside the tool call, not by asking the model again.** One bounded wait per command, then
  report the outcome. Repeated "is it done yet" turns are the default failure mode.
- **Bound every wait**, below whatever timeout your tool imposes. A timeout is a result: report
  "still running after N seconds", never "done".
- **The exit code is the verdict.** A success string in a log is not: logs go stale, and a command
  can print `SUCCESS` and still exit non-zero.
- **Use a fresh marker per job.** Remove it before starting, and make sure a marker you find belongs
  to the current run (timestamp or PID), not a previous one.
- **Do not retry a job that appears to have timed out** until you have checked whether the original
  is still running. Starting a second build or a second install while the first is alive corrupts
  outputs and produces confusing results.
- **After an interruption** (cancelled command, tool timeout, dropped connection), re-inspect state
  before retrying: builds, package installs and file writes can be half-applied.
- **Keep diagnostics, not progress.** Send output to a log and print only the exit status plus the
  last ~20 lines on failure. Never stream a whole build log into the conversation.

Pattern (POSIX shell; choose a bound under your tool's timeout):

```sh
MARKER=/tmp/job.exit; LOG=/tmp/job.log
rm -f "$MARKER"                                  # never trust a stale marker
( { <command>; } > "$LOG" 2>&1; echo $? > "$MARKER" ) &
timeout 540 sh -c 'while [ ! -f "$MARKER" ]; do sleep 2; done'
rc=$?
[ "$rc" = 124 ] && echo "STILL RUNNING after 540s — check before retrying"
echo "exit=$(cat "$MARKER" 2>/dev/null || echo unknown)"
tail -n 20 "$LOG"
```

Two notes that are easy to get wrong:

- `kill -0 $pid` is not a completion test for your own child — a finished child stays a zombie until
  reaped. Wait on a marker file instead.
- `a && b &` backgrounds the whole chain, so any variable you set before the `&` is not visible
  afterwards. Keep the backgrounded job on its own line.

For GitHub: `gh pr checks <n> --watch --interval 10` blocks by itself. Exit `1` also means
"no checks reported" — distinguish that from a failed check before reporting either one.
`mergeable` is `UNKNOWN` briefly after a push; block on it rather than re-asking.

## Building and testing

- `sh scripts/build.sh <task>` is the entry point. Use that form; it also works when the checkout sits
  on a filesystem mounted `noexec` (shared storage on Android), where `./scripts/build.sh` fails.
- **Tests only count if they ran.** Force re-execution (for Gradle, `--rerun-tasks`) and clear old
  results before reading totals; otherwise you can be quoting a previous run.
- Unit tests prove rules and state machines. They never prove microphone, Bluetooth or battery
  behaviour — that requires the device playtest in `docs/playtest.md`.

## Review and merge

- Do not merge pull requests, close issues, or push to `main` unless explicitly asked.
- **Write `Part of #N` or `Refs #N` for partial work, never a negated closing keyword.** GitHub
  matches `close`/`closes`/`closed`, `fix`/`fixes`/`fixed` and `resolve`/`resolves`/`resolved`
  followed by `#N` literally, with no negation: a description saying "does not close #1" still links
  and closes #1 on merge. The same applies to commit messages. To unlink, edit the description to
  remove the keyword.
- Keep changes scoped. Do not change audio behaviour — detection thresholds, microphone ownership,
  TTS routing — as a side effect of unrelated work.
- Report what you verified and how. On failure, quote the failing lines instead of summarising the
  error away, and do not repeat an identical failing command unchanged.
