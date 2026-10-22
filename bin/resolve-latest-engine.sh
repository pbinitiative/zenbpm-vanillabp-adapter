#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Resolves the engine's newest build of its main, the moving tag ghcr.io/pbinitiative/zenbpm:dev,
# into the two values the build reads the engine from, so that the integration tests can run
# against it instead of the pin:
#
#   image=ghcr.io/pbinitiative/zenbpm@sha256:<digest>    for -Dzenbpm.image
#   commit=<the 40 characters of the image's revision>   for -Dzenbpm.commit
#
# The tests refuse an image which is not pinned by its digest, because a tag can move between two
# pulls of one build, and they hold the engine to the commit it reports, so both values come from
# the same pulled image. The script then starts that image once and reads the commit the engine
# itself reports, which has to be the start of the revision label: the line it writes names what
# the engine said, not only what its label claims, and the version it reports. Every build of the
# engine's main between two version bumps reports the same version, so the commit is what tells
# two builds apart.
#
# On GitHub Actions the two values go to the step's outputs and the line to the job's summary.
# Run by hand, everything is printed. The script needs Docker, curl and jq; it fails when the
# image cannot be pulled, carries no revision, or reports another commit than its revision.
set -euo pipefail

tag="${1:-ghcr.io/pbinitiative/zenbpm:dev}"
repository="${tag%:*}"
root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

pin="$(sed -nE 's|.*<zenbpm\.commit>([0-9a-f]{40})</zenbpm\.commit>.*|\1|p' "$root/pom.xml")"
if [ -z "$pin" ]; then
  echo "The root pom.xml has no 'zenbpm.commit' of 40 hexadecimal characters." >&2
  exit 1
fi

docker pull --quiet "$tag" >/dev/null

# The same image can be known locally under several names; the digest wanted is the one of this
# repository.
image="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$tag" \
  | grep -E "^${repository}@sha256:[0-9a-f]{64}$" | head -n 1 || true)"
if [ -z "$image" ]; then
  echo "Docker names no digest of $repository for $tag." >&2
  exit 1
fi

commit="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$tag")"
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "$tag carries no label 'org.opencontainers.image.revision' of 40 hexadecimal characters, but '$commit'." >&2
  exit 1
fi

# One node which elects itself leader, as EngineUnderTest starts it. Only the REST port is
# published, to the loopback interface and a port Docker picks.
container="$(docker run --detach --rm \
  --env REST_API_ADDR=:8080 --env GRPC_API_ADDR=:9090 --env CLUSTER_RAFT_BOOTSTRAP_EXPECT=1 \
  --publish 127.0.0.1::8080 "$image")"
trap 'docker stop "$container" >/dev/null' EXIT
port="$(docker port "$container" 8080/tcp | head -n 1)"
port="${port##*:}"

# The engine took six seconds to answer on a laptop; two minutes is where EngineUnderTest gives
# up as well.
reported=""
version=""
for _ in $(seq 120); do
  if status="$(curl --silent --fail --max-time 5 "http://127.0.0.1:$port/system/status")"; then
    reported="$(jq --raw-output '.git.commitId // empty' <<<"$status")"
    version="$(jq --raw-output '.build.version // empty' <<<"$status")"
    break
  fi
  sleep 1
done
if [ -z "$reported" ] || [[ "$commit" != "$reported"* ]]; then
  echo "$image is labelled with commit $commit, but the engine reports '$reported'." >&2
  exit 1
fi

if [ "$commit" = "$pin" ]; then
  against="the pinned commit"
else
  against="not the pinned commit $pin"
fi
line="Latest engine: \`$tag\` is \`$image\`, built from commit \`$commit\`, which the engine reported as \`$reported\` with version \`${version:-none}\`; $against."

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "image=$image" >>"$GITHUB_OUTPUT"
  echo "commit=$commit" >>"$GITHUB_OUTPUT"
fi
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "$line" >>"$GITHUB_STEP_SUMMARY"
fi
echo "image=$image"
echo "commit=$commit"
echo "$line"
