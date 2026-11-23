#!/usr/bin/env bash
# Says how far a build is and how long it still has, where it can be seen without opening the log:
# as a commit status ("build progress") on the commit being built, which shows beside the commit on
# GitHub and in its app, and as a notice on the run's page.
#
#   progress.sh at <percent> "<what is happening>"
#   progress.sh run <from> <to> "<what is happening>" -- <command...>
#   progress.sh done
#   progress.sh failed
#
# "run" is for a long step: it runs the command and, while it runs, moves the percentage from
# <from> towards <to> every 30 seconds, by how long the step has taken against how long it usually
# takes. The time left comes from how long this workflow's last successful runs took, so it is an
# estimate: a run with a cold cache takes longer than it says.
#
# Needs GH_TOKEN in the environment and, in the workflow, `statuses: write` and `actions: read`.
# Nothing here can fail the build: every request is best-effort.
set -u

context="build progress"
run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"

# Seconds a successful run of this workflow usually takes (mean of the last six on main), or 0.
typical() {
  local cache="$RUNNER_TEMP/progress-typical-seconds"
  if [ ! -s "$cache" ]; then
    local workflow="${GITHUB_WORKFLOW_REF%%@*}"
    workflow="${workflow##*/}"
    gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$workflow/runs?status=success&branch=main&per_page=6" \
      -q '[.workflow_runs[] | ((.updated_at | fromdate) - (.run_started_at | fromdate))] | if length > 0 then (add / length | floor) else 0 end' \
      > "$cache" 2>/dev/null || echo 0 > "$cache"
    grep -Eq '^[0-9]+$' "$cache" || echo 0 > "$cache"
  fi
  cat "$cache"
}

# describe <percent> <label>: "45% · Building the APK · about 6 min left"
describe() {
  local percent="$1" label="$2" total left=""
  total="$(typical)"
  if [ "$total" -gt 0 ] && [ "$percent" -lt 100 ]; then
    local seconds=$(( total * (100 - percent) / 100 ))
    if [ "$seconds" -ge 90 ]; then
      left=" · about $(( (seconds + 30) / 60 )) min left"
    else
      left=" · under a minute left"
    fi
  fi
  echo "${percent}% · ${label}${left}"
}

# status <state> <description>
status() {
  gh api -X POST "repos/$GITHUB_REPOSITORY/statuses/$GITHUB_SHA" \
    -f state="$1" -f context="$context" -f description="${2:0:140}" -f target_url="$run_url" \
    > /dev/null 2>&1 || true
}

case "${1:-}" in
  at)
    text="$(describe "$2" "$3")"
    status pending "$text"
    echo "::notice title=Build progress::$text"
    ;;
  run)
    from="$2"; to="$3"; label="$4"; shift 4
    [ "${1:-}" = "--" ] && shift
    total="$(typical)"
    expected=$(( total * (to - from) / 100 ))
    [ "$expected" -lt 30 ] && expected=30
    text="$(describe "$from" "$label")"
    status pending "$text"
    echo "::notice title=Build progress::$text"
    (
      started=$(date +%s)
      while sleep 30; do
        elapsed=$(( $(date +%s) - started ))
        percent=$(( from + (to - from) * elapsed / expected ))
        # Never the step's end before the step has ended: a slow run sits one short of it.
        [ "$percent" -ge "$to" ] && percent=$(( to - 1 ))
        text="$(describe "$percent" "$label")"
        status pending "$text"
        echo "[progress] $text"
      done
    ) &
    ticker=$!
    "$@"
    code=$?
    # The ticker's sleep too: left running it would hold the step open for up to 30 seconds.
    pkill -P "$ticker" 2> /dev/null
    kill "$ticker" 2> /dev/null
    wait "$ticker" 2> /dev/null
    exit "$code"
    ;;
  done)
    started="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" -q '.run_started_at | fromdate' 2> /dev/null || echo 0)"
    took=""
    if [ "${started:-0}" -gt 0 ]; then
      seconds=$(( $(date +%s) - started ))
      took=" in $(( seconds / 60 )) min $(( seconds % 60 )) s"
    fi
    status success "100% · done$took"
    echo "::notice title=Build progress::100% · done$took"
    ;;
  failed)
    status failure "The build failed"
    ;;
  *)
    echo "usage: progress.sh at <percent> <label> | run <from> <to> <label> -- <command...> | done | failed" >&2
    exit 2
    ;;
esac
