#!/usr/bin/env bash
# Renames the product in this repository: visible name, application identifier,
# Kotlin/Java/AIDL package (folders included), theme names, file names, link
# scheme, helper process name, scripts, tests, workflows, docs.
#
# Usage (from anywhere inside the repository, on a clean tree):
#   bash scripts/rename-product.sh
#   bash scripts/check-rename.sh      # must print nothing and exit 0
#
# Safe to run once. A second run says "already renamed" and changes nothing.
# Only bash, git, perl, grep, find are used.
set -euo pipefail

# ---------------------------------------------------------------- parameters
OLD_NAME="OpenCyvis"        # product name as written in code
NEW_NAME="OpenCyclon"
OLD_SPACED="Open Cyvis"     # the name written as two words
NEW_SPACED="Open Cyclon"
OLD_SHORT="Cyvis"           # the short name (bare mentions, the spoken name)
NEW_SHORT="Cyclon"

# ---------------------------------------------------------------- keep-list
# Whole files the script never touches.
KEEP_FILES=(
  NOTICE                     # names the origin project and keeps its copyright
  LICENSE                    # copyright line of the origin project
  ARCHITECTURE.md            # updated by the PM
  UI.md                      # updated by the PM
  'pm/**'                    # updated by the PM
  scripts/rename-product.sh  # this script (holds the old name as a parameter)
  scripts/check-rename.sh
)
OLD_LC=$(printf '%s' "$OLD_NAME" | tr '[:upper:]' '[:lower:]')
# Substrings left as they are wherever they appear (perl regex, case-insensitive):
# the upstream project's addresses (git remote, releases holding the speech
# model download, docs site) and the upstream repository folder name.
KEEP_SPANS="https?://(?:[\\w.-]+\\.)?github\\.com/${OLD_LC}/[^\\s)\"'<>\`]*|https?://(?:[\\w-]+\\.)?${OLD_LC}\\.ai\\b[^\\s)\"'<>\`]*|\\b${OLD_LC}-phone\\b"
# Whole lines left as they are, in any file: the upstream commit reference.
KEEP_LINES="3acd0a5"
# Whole lines left as they are in CHANGELOG.md: history entries.
KEEP_LINES_CHANGELOG="initial (?:open-source )?release|dumpsys ${OLD_LC}\\b"

# Perl filter shared with check-rename.sh. Reads file names on stdin.
#   mode=rename : rewrites each file, prints the names of changed files
#   mode=check  : prints file:line:text for every occurrence outside the keep-list
PERL_FILTER='
use strict; use warnings;
my $mode = shift @ARGV;
my ($ks, $kl, $klc) = map { qr/$_/i } @ENV{qw(RN_KEEP_SPANS RN_KEEP_LINES RN_KEEP_LINES_CHANGELOG)};
my @pairs = split /\n/, $ENV{RN_PAIRS};
my $old = qr/$ENV{RN_OLD_RE}/i;
while (my $f = <STDIN>) {
  chomp $f; next unless -f $f;
  open my $in, "<:raw", $f or die "$f: $!";
  my @lines = <$in>; close $in;
  my ($changed, $n) = (0, 0);
  for my $line (@lines) {
    $n++;
    next if $line =~ $kl || ($f eq "CHANGELOG.md" && $line =~ $klc);
    my @p; my $t = $line;
    $t =~ s{($ks)}{push @p, $1; "\x01" . $#p . "\x02"}ge;
    if ($mode eq "check") {
      if ($t =~ $old) { my $s = $line; $s =~ s/\s+$//; print "$f:$n:$s\n"; }
      next;
    }
    for my $pr (@pairs) { my ($a, $b) = split /\t/, $pr; $t =~ s/\Q$a\E/$b/g; }
    $t =~ s{\x01(\d+)\x02}{$p[$1]}g;
    if ($t ne $line) { $line = $t; $changed = 1; }
  }
  if ($mode eq "rename" && $changed) {
    open my $out, ">:raw", $f or die "$f: $!"; print $out @lines; close $out;
    print "$f\n";
  }
}
'

lc() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
uc() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }
ucfirst_lc() { local l; l=$(lc "$1"); printf '%s%s' "$(uc "${l:0:1}")" "${l:1}"; }

# Replacement pairs, most specific first. Every case variant of the old name.
RN_PAIRS=""
add_pair() { [ "$1" = "$2" ] || RN_PAIRS+="$1"$'\t'"$2"$'\n'; }
for v in "$OLD_SPACED:$NEW_SPACED" "$OLD_NAME:$NEW_NAME" "$OLD_SHORT:$NEW_SHORT"; do
  o=${v%%:*}; n=${v#*:}
  add_pair "$o" "$n"
  add_pair "$(lc "$o")" "$(lc "$n")"
  add_pair "$(uc "$o")" "$(uc "$n")"
  add_pair "$(ucfirst_lc "$o")" "$(ucfirst_lc "$n")"
done
RN_PAIRS=${RN_PAIRS%$'\n'}
# What counts as an occurrence of the old name (case-insensitive).
RN_OLD_RE="$(lc "$OLD_SHORT")|$(lc "$OLD_NAME")"
export RN_PAIRS RN_OLD_RE RN_KEEP_SPANS="$KEEP_SPANS" RN_KEEP_LINES="$KEEP_LINES" \
  RN_KEEP_LINES_CHANGELOG="$KEEP_LINES_CHANGELOG"

keep_pathspecs() { local k; for k in "${KEEP_FILES[@]}"; do printf ':(exclude,glob)%s\n' "$k"; done; }
new_path() { # applies the replacement pairs to a path
  local p=$1 pr
  while IFS=$'\t' read -r a b; do p=${p//"$a"/"$b"}; done <<< "$RN_PAIRS"
  printf '%s' "$p"
}

# check-rename.sh sources this file for the definitions above.
if [ "${RENAME_DEFS_ONLY:-0}" = 1 ]; then return 0 2>/dev/null || exit 0; fi

# ---------------------------------------------------------------- guards
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
OLD_PKG=ai/$OLD_LC
NEW_PKG=ai/$(lc "$NEW_NAME")
TRACKED=$(git ls-files)
if ! grep -q "/$OLD_PKG/" <<< "$TRACKED" && grep -q "/$NEW_PKG/" <<< "$TRACKED"; then
  echo "already renamed: no tracked path under $OLD_PKG, sources are under $NEW_PKG. Nothing to do."
  exit 0
fi
dirty=$(git status --porcelain | grep -v -E '^\?\? scripts/(rename-product|check-rename)\.sh$' || true)
if [ -n "$dirty" ]; then
  echo "refusing to run: the working tree is not clean. Commit or discard these first:" >&2
  echo "$dirty" >&2
  exit 1
fi
echo "Renaming $OLD_NAME -> $NEW_NAME in $ROOT"

# ---------------------------------------------------------------- 1. move files
moved=0
while IFS= read -r f; do
  t=$(new_path "$f")
  [ "$t" = "$f" ] && continue
  mkdir -p "$(dirname "$t")"
  git mv "$f" "$t"
  moved=$((moved + 1))
  d=$(dirname "$f")
  rmdir -p "$d" 2>/dev/null || true
done < <(git ls-files -- $(keep_pathspecs) | grep -i -E "$RN_OLD_RE" || true)

# ---------------------------------------------------------------- 2. replace text
changed_list=$(git grep -l -I -i -E "$RN_OLD_RE" -- . $(keep_pathspecs) \
  | perl -e "$PERL_FILTER" rename)
changed=$(printf '%s' "$changed_list" | grep -c . || true)

# ---------------------------------------------------------------- 3. patch steps
warn=0
patch_fail() { echo "WARNING: patch step did not apply: $1 (do it by hand, then add it to this script)" >&2; warn=$((warn + 1)); }
# Adds "import <class>" before the first import line (or after the package line).
add_import() {
  RN_IMPORT="$2" perl -0pi -e 'my $i = "import $ENV{RN_IMPORT}";
    unless (/^\Q$i\E$/m) { s/^import /$i\nimport /m or s/^(package [^\n]+\n)/$1\n$i\n/m }' "$1"
}

# ---------------------------------------------------------------- summary
echo
echo "Files moved:   $moved"
echo "Files changed: $changed"
echo
echo "Remaining occurrences of the old name outside the keep-list:"
if bash "$ROOT/scripts/check-rename.sh"; then echo "  none"; fi
if [ "$warn" -gt 0 ]; then echo "$warn patch step(s) need hand work, see the warnings above." >&2; exit 2; fi
echo "Done. Nothing is committed. Build and test, then commit."
