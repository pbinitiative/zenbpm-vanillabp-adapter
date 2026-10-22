#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Prints the files a deploy of this repository publishes in the given version, one per line, as
# <artifactId>/<file name>: the jar, the sources jar and the POM of the core, the Spring Boot module
# and the two Quarkus modules, and the parent's POM. Nothing of the test, report or gate modules.
# bin/check-deploy-safety.sh compares a snapshot deploy with it, bin/release.sh a release.
#
#   bin/published-artifacts.sh 2.0.0
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
  echo "usage: $0 <version>" >&2
  exit 2
fi
version="$1"

{
  for module in zenbpm-vanillabp-adapter zenbpm-vanillabp-adapter-quarkus \
    zenbpm-vanillabp-adapter-quarkus-deployment zenbpm-vanillabp-adapter-spring-boot; do
    echo "$module/$module-$version.jar"
    echo "$module/$module-$version-sources.jar"
    echo "$module/$module-$version.pom"
  done
  echo "zenbpm-vanillabp-adapter-parent/zenbpm-vanillabp-adapter-parent-$version.pom"
} | sort
