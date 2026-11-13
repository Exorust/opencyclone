#!/usr/bin/env bash
# Lists every remaining occurrence of the old product name (all case variants)
# outside the keep-list, in file contents and in file paths.
# Prints nothing and exits 0 when the rename is complete; exits 1 otherwise.
# The names and the keep-list are defined once, at the top of rename-product.sh.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
RENAME_DEFS_ONLY=1 source "$ROOT/scripts/rename-product.sh"

found=$(
  {
    # paths (tracked and untracked, not ignored)
    { git ls-files -- $(keep_pathspecs); git ls-files --others --exclude-standard -- $(keep_pathspecs); } \
      | grep -i -E "$RN_OLD_RE" | sed 's/^/path: /' || true
    # contents
    { git grep -l -I -i -E --untracked --exclude-standard "$RN_OLD_RE" -- . $(keep_pathspecs) || true
    } | sort -u | perl -e "$PERL_FILTER" check
  }
)
if [ -n "$found" ]; then
  printf '%s\n' "$found"
  exit 1
fi
exit 0
