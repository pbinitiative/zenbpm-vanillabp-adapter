#!/usr/bin/env bash
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# One node of the pinned engine for the blueprints, as bin/build-blueprints-on-zenbpm.sh and the
# nightly's job 'blueprints' run them, with its REST API on port 18080 and its job stream on port
# 9090 of this machine: the addresses of the blueprints' 'application-zenbpm.yaml', and of the
# wiki's page 'Home'.
#
#   bin/zenbpm-engine.sh start   # starts the engine and returns once it answers ready
#   bin/zenbpm-engine.sh stop    # removes it with everything it kept
#   bin/zenbpm-engine.sh logs    # prints what it wrote
#   bin/zenbpm-engine.sh image   # prints the image it starts
#
# The image is the one the root POM pins in 'zenbpm.image', read from there on every call, so
# there is no second copy to forget; EnginePinTest in test-coverage-report/coverage-gate holds
# the script to it.

set -o errexit
set -o nounset
set -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER=zenbpm-blueprints-engine
REST_PORT=18080
GRPC_PORT=9090
# It took six seconds on a laptop; this is where a start gives up.
READY_TIMEOUT_SECONDS=120
START_ATTEMPTS=3

image() {
  local pinned
  pinned="$(sed -nE 's|^ *<zenbpm\.image>([^<]+)</zenbpm\.image> *$|\1|p' "${ROOT}/pom.xml")"
  if [ -z "${pinned}" ]; then
    echo "No <zenbpm.image> found in ${ROOT}/pom.xml." >&2
    exit 1
  fi
  echo "${pinned}"
}

start() {
  local pinned attempt
  pinned="$(image)"
  # Another run on this machine, a person's or a test's, uses the same name and ports; removing
  # its engine would fail the twin it builds, or let it pass against a fresh engine.
  if [ "$(docker inspect --format '{{.State.Running}}' "${CONTAINER}" 2>/dev/null)" = "true" ]; then
    echo "The engine ${CONTAINER} is running already, started by another run. End that run, or remove its engine with '$0 stop'." >&2
    exit 1
  fi
  # Now and then the node gives up its own bootstrap ("failed to bootstrap cluster: boot
  # timeout") when the first call it makes to itself times out, and the container ends. On a
  # laptop it happened once in about ten starts; a fresh container boots.
  for attempt in $(seq "${START_ATTEMPTS}"); do
    if started "${pinned}"; then
      echo "The engine ${pinned} answers on http://localhost:${REST_PORT} and its job stream on port ${GRPC_PORT}."
      return 0
    fi
    echo "The engine did not become ready (attempt ${attempt} of ${START_ATTEMPTS}):" >&2
    logs | tail -n 20 >&2
  done
  # the container of the last attempt is this run's, and may still be booting
  stop
  exit 1
}

# Starts a fresh container and waits until it answers ready, gives up or ends. It runs as the
# condition of an 'if', where errexit does not hold, so every failure returns by itself.
started() {
  local waited=0
  stop
  # The settings of the engine the adapter's own integration tests start (EngineUnderTest in
  # engine-test-support): one node which elects itself leader, due timers looked for every
  # second instead of every ten, so a blueprint waiting for a timer does not wait for the
  # engine's poll, and ended instances kept. And three attempts for a task whose model names
  # none, the default of Camunda 8, which the blueprints' models are written for: the engine's
  # own default is one, see the wiki page 'Configuration'.
  docker run --detach --name "${CONTAINER}" \
    --publish "${REST_PORT}:8080" --publish "${GRPC_PORT}:9090" \
    --env REST_API_ADDR=:8080 --env GRPC_API_ADDR=:9090 \
    --env CLUSTER_RAFT_BOOTSTRAP_EXPECT=1 --env POLL_TIMER_DELAY_SECONDS=1 \
    --env PERSISTENCE_INSTANCE_HISTORY_TTL=0 --env JOBS_DEFAULT_RETRIES=3 \
    "$1" >/dev/null || return 1
  # 503 until the node is the leader and its partitions are initialized
  until curl --silent --fail --output /dev/null "http://localhost:${REST_PORT}/system/health/ready"; do
    if [ "${waited}" -ge "${READY_TIMEOUT_SECONDS}" ] \
      || [ "$(docker inspect --format '{{.State.Running}}' "${CONTAINER}")" != "true" ]; then
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

stop() {
  docker rm --force --volumes "${CONTAINER}" >/dev/null 2>&1 || true
}

logs() {
  docker logs "${CONTAINER}" 2>&1 || true
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  logs) logs ;;
  image) image ;;
  *)
    echo "Usage: $0 start|stop|logs|image" >&2
    exit 2
    ;;
esac
