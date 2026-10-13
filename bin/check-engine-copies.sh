#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Checks that the two engine files this repository copies are the engine's files of the pinned
# commit, see decision 18 in the repository's DECISIONS.md:
#
#   core/src/main/zenbpm/api.yaml      = openapi/api.yaml
#   core/src/main/proto/zenbpm.proto   = pkg/zenclient/proto/zenbpm.proto
#
# The commit is 'zenbpm.commit' of the root pom.xml. The engine's files are read from GitHub at
# that commit, so the script needs the network but no engine checkout; the pull-request check
# runs it before the build. Two differences are allowed: the comment lines a file starts with,
# where a copy says where it came from, and in the proto the one 'option java_package' line,
# which puts the generated classes into this adapter's package. Anything else is reported as a
# diff and fails the script.
set -euo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

java_package='option java_package = "org.pbinitiative.zenbpmadapter.client.grpc";'

commit="$(sed -nE 's|.*<zenbpm\.commit>([0-9a-f]{40})</zenbpm\.commit>.*|\1|p' "$root/pom.xml")"
if [ -z "$commit" ]; then
  echo "The root pom.xml has no 'zenbpm.commit' of 40 hexadecimal characters." >&2
  exit 1
fi

# The lines of a file after the comment lines it starts with.
without_header() {
  local file="$1" marker="$2"
  awk -v marker="$marker" 'body || index($0, marker) != 1 { body = 1; print }' "$file"
}

failed=0

# Compares one copy with the engine's file. The fourth argument, where given, is a line the copy
# carries exactly once in addition to the engine's file.
compare() {
  local copy="$1" upstream_path="$2" marker="$3" added_line="${4:-}"
  local upstream="$work/upstream" expected="$work/expected" actual="$work/actual"

  if ! curl --fail --silent --show-error --location --retry 3 \
    --output "$upstream" "https://raw.githubusercontent.com/pbinitiative/zenbpm/$commit/$upstream_path"; then
    echo "FAILED: cannot read $upstream_path of commit $commit from GitHub. Check that 'zenbpm.commit' of the root pom.xml is a commit of pbinitiative/zenbpm which has this file." >&2
    exit 1
  fi

  without_header "$upstream" "$marker" > "$expected"
  without_header "$root/$copy" "$marker" > "$actual"
  if [ -n "$added_line" ]; then
    if [ "$(grep -cxF -- "$added_line" "$actual")" != "1" ]; then
      echo "FAILED: $copy has to carry the line '$added_line' exactly once." >&2
      failed=1
      return
    fi
    grep -vxF -- "$added_line" "$actual" > "$actual.stripped"
    mv "$actual.stripped" "$actual"
  fi

  if diff --unified "$expected" "$actual" > "$work/diff"; then
    echo "ok: $copy is $upstream_path of commit $commit"
  else
    echo "FAILED: $copy differs from $upstream_path of commit $commit ('-' engine, '+' copy):" >&2
    cat "$work/diff" >&2
    failed=1
  fi
}

compare core/src/main/zenbpm/api.yaml openapi/api.yaml '#'
compare core/src/main/proto/zenbpm.proto pkg/zenclient/proto/zenbpm.proto '//' "$java_package"

if [ "$failed" != 0 ]; then
  echo "Copy the engine's files of exactly commit $commit again, see the README, section 'Supported engine version'." >&2
  exit 1
fi
