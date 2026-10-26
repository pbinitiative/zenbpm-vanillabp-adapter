#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Reads what the jobs of the nightly build on, and gives each job a key which changes as soon as
# one of its inputs changes, so a job runs only where something it builds on is new (see decision
# 68 in the repository's DECISIONS.md):
#
#   adapter      the commit of this checkout
#   vanillabp    the newest snapshot of every VanillaBP artifact the root POM names, by its
#                timestamp and build number in the snapshot repository of Maven Central
#   engine       the digest of ghcr.io/pbinitiative/zenbpm:dev, the engine's newest build of its main
#   blueprints   the newest commit of the blueprints' main, which bin/build-blueprints-on-zenbpm.sh
#                clones
#   week         the year and the week of the year, a week beginning on Sunday
#
#   pinned-engine-key and native-image-key   week, adapter and vanillabp
#   latest-engine-key                        week, adapter, vanillabp and engine
#   blueprints-key                           week, adapter, vanillabp and blueprints
#
# The week is in every key, so the run of Sunday finds no key saved before it. A build of Sunday
# which fails saves nothing, and the job runs again on Monday, also where its inputs did not change.
# The first of January begins a week as well, and every job runs once more.
#
# The pinned engine is no input of its own: it moves only with a commit of this repository. An
# input which cannot be read is written as 'unread' and leaves the key of every job which builds on
# it empty, so that job runs: not knowing what changed is no reason to skip a build. The script
# needs curl, date, git, jq, sha256sum and timeout, and no Docker and no login: the registry, the
# snapshot repository and the blueprints are read anonymously.
#
# On GitHub Actions everything goes to the step's outputs. Run by hand, it is printed.
set -euo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
SNAPSHOTS=https://central.sonatype.com/repository/maven-snapshots
ENGINE_REPOSITORY=pbinitiative/zenbpm
ENGINE_TAG=dev
UNREAD=unread

# --fail turns an answer of 4xx or 5xx into a failure; a repository which does not answer within a
# minute counts as unread, and the job runs
read_url() {
  curl --silent --show-error --fail --location --max-time 60 --retry 2 "$@"
}

adapter="$(git -C "$root" rev-parse HEAD)"

# Every io.vanillabp artifact of the root POM with the version it names there, one 'artifact
# version' per line, the version's property resolved from the POM's properties. The modules and
# the blueprints read further VanillaBP artifacts, such as bpms-double-quarkus or vanillabp-bom.
# Those are no input of their own: spi-for-java and adapter-platform-integration each publish all
# their modules in one deploy from their main, so a new snapshot of one of those artifacts comes
# with a new snapshot of an artifact of the same repository which the root POM names.
vanillabp_artifacts() {
  awk '
    /<groupId>io\.vanillabp<\/groupId>/ { vanillabp = 1; artifact = ""; next }
    vanillabp && /<artifactId>/ { artifact = $0; gsub(/.*<artifactId>|<\/artifactId>.*/, "", artifact); next }
    vanillabp && /<version>/ && artifact != "" {
      version = $0; gsub(/.*<version>|<\/version>.*/, "", version); print artifact, version; vanillabp = 0
    }
    /<\/dependency>/ { vanillabp = 0 }
  ' "$root/pom.xml" | LC_ALL=C sort -u | while read -r artifact version; do
    if [[ "$version" =~ ^\$\{(.+)\}$ ]]; then
      property="${BASH_REMATCH[1]}"
      version="$(sed -nE "s|.*<${property//./\\.}>([^<]+)</${property//./\\.}>.*|\1|p" "$root/pom.xml" | head -n 1)"
    fi
    echo "$artifact $version"
  done
}

# A snapshot is named by the timestamp and build number of its newest upload, a release by itself:
# a release never changes.
vanillabp=""
while read -r artifact version; do
  if [[ "$version" == *-SNAPSHOT ]]; then
    metadata="$(read_url "$SNAPSHOTS/io/vanillabp/$artifact/$version/maven-metadata.xml" || true)"
    timestamp="$(sed -nE 's|.*<timestamp>([0-9.]+)</timestamp>.*|\1|p' <<<"$metadata" | head -n 1)"
    build="$(sed -nE 's|.*<buildNumber>([0-9]+)</buildNumber>.*|\1|p' <<<"$metadata" | head -n 1)"
    if [ -n "$timestamp" ] && [ -n "$build" ]; then
      version="${version%-SNAPSHOT}-$timestamp-$build"
    else
      echo "::warning::The snapshot repository names no upload of io.vanillabp:$artifact:$version." >&2
      vanillabp="$UNREAD"
      break
    fi
  fi
  vanillabp="${vanillabp:+$vanillabp, }$artifact $version"
done < <(vanillabp_artifacts)
if [ -z "$vanillabp" ]; then
  echo "::warning::The root pom.xml names no io.vanillabp artifact." >&2
  vanillabp="$UNREAD"
fi

# The digest the registry answers for the tag, which is the one 'docker pull' gives as well. A
# public image needs a token all the same, which the registry hands out without a login.
token="$(read_url "https://ghcr.io/token?scope=repository:$ENGINE_REPOSITORY:pull" | jq --raw-output '.token // empty' || true)"
engine="$(read_url --head \
  --header "Authorization: Bearer $token" \
  --header "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json" \
  "https://ghcr.io/v2/$ENGINE_REPOSITORY/manifests/$ENGINE_TAG" \
  | sed -nE 's|^docker-content-digest: *(sha256:[0-9a-f]{64}).*|\1|Ip' | head -n 1 || true)"
if [ -z "$engine" ]; then
  echo "::warning::ghcr.io names no digest of $ENGINE_REPOSITORY:$ENGINE_TAG." >&2
  engine="$UNREAD"
fi

# the repository the blueprints are cloned from, read from the script which clones them
blueprints_repository="$(sed -nE 's|^REPOSITORY=(.+)$|\1|p' "$root/bin/build-blueprints-on-zenbpm.sh")"
# a repository which does not answer within a minute counts as unread, as with curl
blueprints="$(timeout 60 git ls-remote "$blueprints_repository" refs/heads/main 2>/dev/null | cut -f 1 || true)"
if ! [[ "$blueprints" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::warning::$blueprints_repository names no commit of its main." >&2
  blueprints="$UNREAD"
fi

# %U counts the weeks of the year from its first Sunday, so a new week begins on every Sunday
week="$(date -u +%Y-%U)"

# nightly-<job>-<SHA-256 of its inputs>, or nothing where one of them is unread
key() {
  local job="$1"
  shift
  for input in "$@"; do
    if [ "$input" = "$UNREAD" ]; then
      return
    fi
  done
  echo "nightly-$job-$(printf '%s\n' "$@" | sha256sum | cut -c 1-64)"
}

outputs="adapter=$adapter
vanillabp=$vanillabp
engine=$engine
blueprints=$blueprints
week=$week
pinned-engine-key=$(key pinned-engine "$week" "$adapter" "$vanillabp")
latest-engine-key=$(key latest-engine "$week" "$adapter" "$vanillabp" "$engine")
native-image-key=$(key native-image "$week" "$adapter" "$vanillabp")
blueprints-key=$(key blueprints "$week" "$adapter" "$vanillabp" "$blueprints")"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "$outputs" >>"$GITHUB_OUTPUT"
fi
echo "$outputs"
