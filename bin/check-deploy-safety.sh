#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Proves what publish-snapshots.yaml relies on, without GitHub and without credentials:
#
#   1. A green build deploys exactly the published files: the jar, the sources jar and the POM of
#      the core, the Spring Boot module and the two Quarkus modules, and the parent's POM. No file
#      missing, and nothing else, so nothing of the test, report or gate modules either.
#   2. A red coverage gate deploys nothing at all, although every module before the gate built.
#
# Both runs work on a copy of the working tree (tracked and modified files) and deploy into a
# directory of their own. Neither installs into the local Maven repository, so the untested
# method of the second run never reaches ~/.m2. PublicationSafetyTest checks on every build that
# the settings this rests on are still there; run this script after changing anything about
# deploying, the module order or the gate. It needs spi-for-java and adapter-platform-integration
# in the local Maven repository, like any build here, and takes two full builds.
set -euo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

copy_working_tree() {
  local target="$1"
  mkdir -p "$target"
  (cd "$root" && git ls-files -z --cached --others --exclude-standard | xargs -0 cp --parents --target-directory="$target")
}

deploy() {
  local source="$1" destination="$2" log="$3"
  shift 3
  (cd "$source" && mvn --batch-mode --no-transfer-progress \
    -Dmaven.install.skip=true \
    -DaltDeploymentRepository="github::file://$destination" \
    "$@" \
    deploy) > "$log" 2>&1
}

# The published files, with the snapshot timestamp of each upload replaced by SNAPSHOT. A library
# module publishes its jar, its sources jar and its POM, the parent its POM alone.
expected="$("$root/bin/published-artifacts.sh" 2.0.0-SNAPSHOT)"

# Every file a deploy wrote below the groupId, except what Maven adds to each upload itself
# (maven-metadata.xml and the checksums), as <artifactId>/<file name> with the timestamp of the
# snapshot replaced by SNAPSHOT, so a missing or an additional artifact shows in the comparison.
published_files() {
  local repository="$1/org/pbinitiative/zenbpm"
  [ -d "$repository" ] || return 0
  find "$repository" -type f \
    ! -name 'maven-metadata*' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
    -printf '%P\n' |
    sed -E 's#^([^/]+)/[^/]+/#\1/#; s#-2\.0\.0-[0-9]{8}\.[0-9]{6}-[0-9]+#-2.0.0-SNAPSHOT#' |
    sort
}

echo "1/2 green build: deploys exactly the published artifacts"
copy_working_tree "$work/green-source"
if ! deploy "$work/green-source" "$work/green" "$work/green.log"; then
  tail -40 "$work/green.log"
  echo "FAILED: the green build did not succeed, see the log above" >&2
  exit 1
fi
deployed="$(published_files "$work/green")"
if [ "$deployed" != "$expected" ]; then
  echo "FAILED: the green build did not deploy exactly the published files:" >&2
  diff <(echo "$expected") <(echo "$deployed") | sed -n 's/^< /    missing:    /p; s/^> /    unexpected: /p' >&2
  exit 1
fi
echo "    ok: $(echo "$deployed" | wc -l) files, the jar, sources jar and POM of four modules and the parent POM"

echo "2/2 red coverage gate: deploys nothing"
copy_working_tree "$work/red-source"
cat > "$work/red-source/core/src/main/java/org/pbinitiative/zenbpmadapter/UntestedProbe.java" <<'JAVA'
/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

/**
 * Written by bin/check-deploy-safety.sh into a copy of the repository: code no test covers.
 */
public final class UntestedProbe {

    private UntestedProbe() {
    }

    /**
     * Never called by a test.
     *
     * @return always true
     */
    public static boolean isNeverTested() {
        return true;
    }

}
JAVA
# One untested method is a few instructions among all the covered ones, so it cannot pull the
# coverage below the real thresholds. The red run asks for 100 % instead: then that method alone
# turns the gate red, however much of the adapter is tested.
if deploy "$work/red-source" "$work/red" "$work/red.log" \
  -Dcoverage.threshold.spring-boot=100 -Dcoverage.threshold.quarkus=100; then
  echo "FAILED: the build with an untested method passed the coverage gate" >&2
  exit 1
fi
if ! grep -q "below the 100 % at which every VanillaBP build stops" "$work/red.log"; then
  tail -40 "$work/red.log"
  echo "FAILED: the build failed, but not at the coverage gate, see the log above" >&2
  exit 1
fi
if [ -n "$(find "$work/red" -type f 2>/dev/null)" ]; then
  echo "FAILED: a red coverage gate left artifacts deployed:" >&2
  find "$work/red" -type f >&2
  exit 1
fi
echo "    ok: the gate failed and nothing was deployed"
