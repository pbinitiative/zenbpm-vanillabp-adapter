#!/usr/bin/env bash
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Builds the VanillaBP blueprints with '-Pzenbpm' against the pinned engine, as the nightly's job
# 'blueprints' does (see decision 64 in the repository's DECISIONS.md):
#
#   bin/build-blueprints-on-zenbpm.sh <directory> [<twin>...]
#
# It clones the newest 'main' of vanillabp-blueprints/blueprints into <directory>, which must not
# exist yet, applies the overlay of blueprints-overlay/ to it, and builds every twin the overlay
# lists, or only the twins named (for example 'blueprints-bpmn/bpmn-timer/quarkus'), each with
# 'mvn -Pzenbpm verify' against an engine of its own which bin/zenbpm-engine.sh starts and
# removes again: the twins of a blueprint deploy the same processes under the same identifiers,
# and a twin must not find the workflows, timers and versions the one before it left. It reads
# the adapter from the local Maven repository, so install it from this checkout first:
#
#   mvn -Dmaven.test.skip=true --projects spring-boot,quarkus/deployment --also-make install
#
# Every twin is built, also after one failed. The script ends with 1 where the overlay does not
# fit the checkout or a twin failed, and writes into <directory>:
#   zenbpm-results.txt   one line per twin: '<twin> passed|failed <seconds>'
#   zenbpm-logs/         the build's output of every twin, and the engine's of a failed one
#
# 'verify', not 'install': a twin is a reactor of its own, and the blueprints changed by the
# overlay have no business in the local Maven repository.

set -o errexit
set -o nounset
set -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY=https://github.com/vanillabp-blueprints/blueprints

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <directory to clone the blueprints into> [<twin>...]" >&2
  exit 2
fi
CHECKOUT="$1"
shift
if [ -e "${CHECKOUT}" ]; then
  echo "${CHECKOUT} exists; name a directory which does not, the blueprints are cloned fresh." >&2
  exit 2
fi

git clone --quiet --depth 1 "${REPOSITORY}" "${CHECKOUT}"
CHECKOUT="$(cd "${CHECKOUT}" && pwd)"
echo "vanillabp-blueprints/blueprints at $(git -C "${CHECKOUT}" rev-parse HEAD)"

# the Java of this build runs the overlay as a source file
java "${ROOT}/blueprints-overlay/ZenBpmOverlay.java" "${ROOT}/blueprints-overlay" "${CHECKOUT}"

if [ "$#" -gt 0 ]; then
  twins=("$@")
else
  mapfile -t twins <"${CHECKOUT}/zenbpm-twins.txt"
fi

RESULTS="${CHECKOUT}/zenbpm-results.txt"
LOGS="${CHECKOUT}/zenbpm-logs"
mkdir -p "${LOGS}"
: >"${RESULTS}"
failed=0
# only an engine this run started: one bin/zenbpm-engine.sh refused to start belongs to another run
own_engine=false
trap 'if [ "${own_engine}" = true ]; then "${ROOT}/bin/zenbpm-engine.sh" stop; fi' EXIT

for twin in "${twins[@]}"; do
  if ! grep --quiet --line-regexp --fixed-strings "${twin}" "${CHECKOUT}/zenbpm-twins.txt"; then
    echo "${twin} is no twin the overlay builds, see ${CHECKOUT}/zenbpm-twins.txt." >&2
    exit 2
  fi
  log="${LOGS}/${twin//\//_}.log"
  started="${SECONDS}"
  echo "::group::${twin}"
  "${ROOT}/bin/zenbpm-engine.sh" start
  own_engine=true
  # --update-snapshots, as the blueprints' own CI does: the VanillaBP platform is a snapshot,
  # and a local repository would keep yesterday's for the rest of the day
  if (cd "${CHECKOUT}/${twin}" && mvn --settings "${ROOT}/blueprints-overlay/settings.xml" \
    --batch-mode --no-transfer-progress --update-snapshots -Pzenbpm verify) >"${log}" 2>&1; then
    result=passed
  else
    result=failed
    failed=$((failed + 1))
    "${ROOT}/bin/zenbpm-engine.sh" logs >"${LOGS}/${twin//\//_}.engine.log"
    tail -n 60 "${log}"
  fi
  "${ROOT}/bin/zenbpm-engine.sh" stop
  own_engine=false
  echo "${twin} ${result} $((SECONDS - started))" | tee -a "${RESULTS}"
  echo "::endgroup::"
done

echo "${failed} of ${#twins[@]} twins failed."
[ "${failed}" -eq 0 ]
