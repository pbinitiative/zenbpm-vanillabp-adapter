#!/usr/bin/env bash
#
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# The steps of .github/workflows/release.yaml, one per command, so that a dry run, a person and the
# tests run exactly what a release runs. See decision 65 in the repository's DECISIONS.md for the
# release, and see decision 66 in the repository's DECISIONS.md for the engine it names.
#
#   bin/release.sh version [<tag>]          prints the version a tag releases, v2.0.0 -> 2.0.0;
#                                           without a tag the one the root POM's revision leads to
#   bin/release.sh engine                   refuses an engine pin which is no engine release
#   bin/release.sh notes <tag> <file>       writes the message of the annotated tag on GitHub
#   bin/release.sh stage <version> <dir>    builds, tests and deploys that version into <dir>/repository
#                                           and checks that exactly the published files arrived
#   bin/release.sh dependencies <dir>       refuses a staged POM which names a snapshot
#   bin/release.sh rehearse <dir>           uploads the staged files into a local repository and
#                                           compares what arrived with what was staged
#   bin/release.sh upload <dir> <url>       uploads the staged files to the Maven repository at <url>
#
# A command which refuses says why and how to fix it on standard error, and on GitHub Actions in
# the job's summary as well, and ends with status 1. 'engine' and 'notes' read GitHub and the
# engine's container registry; 'notes' needs the GitHub CLI and a token in GH_TOKEN.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine_repository="https://github.com/pbinitiative/zenbpm.git"
engine_image="ghcr.io/pbinitiative/zenbpm"
group_path="org/pbinitiative/zenbpm"

refuse() {
  echo "$1" >&2
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n\n' "$1" >>"$GITHUB_STEP_SUMMARY"
  fi
  exit 1
}

usage() {
  sed -n '/^#   bin\/release.sh version/,/^#   bin\/release.sh upload/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

# The value of a property of the root POM, or nothing where it has none.
property() {
  local name="${1//./\\.}"
  sed -nE "s|^ *<${name}>([^<]*)</${name}> *$|\\1|p" "$root/pom.xml" | head -n 1
}

# Every file below the groupId of a Maven repository, as <artifactId>/<file name>, except what Maven
# adds to an upload itself: the metadata and the checksums.
published_files() {
  local repository="$1/$group_path"
  [ -d "$repository" ] || return 0
  find "$repository" -type f \
    ! -name 'maven-metadata*' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
    -printf '%P\n' |
    sed -E 's#^([^/]+)/[^/]+/#\1/#' |
    sort
}

valid_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

version() {
  local tag="${1:-}"
  if [ -z "$tag" ]; then
    local revision
    revision="$(property revision)"
    tag="v${revision%-SNAPSHOT}"
  fi
  if ! [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    refuse "A release tag reads v<major>.<minor>.<patch>, for example v2.0.0, but this one is '$tag'. Delete it and push a tag of that form."
  fi
  echo "${BASH_REMATCH[1]}"
}

engine() {
  local version commit image listed tagged digest token
  version="$(property zenbpm.version)"
  commit="$(property zenbpm.commit)"
  image="$(property zenbpm.image)"
  local fix="Pin an engine release first, in the root pom.xml: 'zenbpm.version' is the engine's release tag (v<major>.<minor>.<patch>), 'zenbpm.commit' the commit that tag points at, and 'zenbpm.image' is $engine_image@sha256:<digest> with the digest of $engine_image:<tag>. Then copy the engine's contract of that commit, as the README's section 'Supported engine version' says."
  if [ -z "$version" ]; then
    refuse "The engine pin is no engine release: the root pom.xml names no 'zenbpm.version', and 'zenbpm.commit' $commit with 'zenbpm.image' $image is a build of the engine's main. A release of this adapter names an engine release as the engine it supports. $fix"
  fi
  if ! [[ "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    refuse "'zenbpm.version' of the root pom.xml is '$version', which names no engine release. $fix"
  fi
  # an annotated tag is listed twice, the second time as the commit it points at
  if ! listed="$(git ls-remote "$engine_repository" "refs/tags/$version" "refs/tags/$version^{}")"; then
    refuse "The tags of the engine could not be read from $engine_repository; run the check again."
  fi
  tagged="$(awk '{ commit = $1 } END { print commit }' <<<"$listed")"
  if [ -z "$tagged" ]; then
    refuse "The engine has no tag '$version', which 'zenbpm.version' of the root pom.xml names. $fix"
  fi
  if [ "$tagged" != "$commit" ]; then
    refuse "The engine's tag $version points at commit $tagged, but 'zenbpm.commit' of the root pom.xml is '$commit'. $fix"
  fi
  # the digest the registry names for the tag, which is the one 'docker pull' records; a registry
  # which answers nothing leaves it empty, and the check below refuses
  token="$(curl --silent --fail "https://ghcr.io/token?scope=repository:${engine_image#ghcr.io/}:pull" |
    sed -nE 's/.*"token":"([^"]+)".*/\1/p' || true)"
  digest="$(curl --silent --fail --head --header "Authorization: Bearer $token" \
    --header "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/${engine_image#ghcr.io/}/manifests/$version" |
    tr -d '\r' | sed -nE 's/^docker-content-digest: *(sha256:[0-9a-f]{64})$/\1/Ip' || true)"
  if [ -z "$digest" ]; then
    refuse "The engine's registry has no image $engine_image:$version. $fix"
  fi
  if [ "$image" != "$engine_image@$digest" ]; then
    refuse "The engine's image $engine_image:$version is $engine_image@$digest, but 'zenbpm.image' of the root pom.xml is '$image'. $fix"
  fi
  echo "The engine pin is the engine release $version: commit $commit, image $image."
}

notes() {
  local tag="$1" file="$2" reference object
  if ! reference="$(gh api "repos/$repository/git/ref/tags/$tag" --jq '.object.type + " " + .object.sha' 2>/dev/null)"; then
    refuse "This repository has no tag '$tag' on GitHub, so there is no message to publish as the release's notes. A release is made by pushing an annotated tag: git tag -a $tag, then git push origin $tag."
  fi
  object="${reference#* }"
  if [ "${reference%% *}" != "tag" ]; then
    refuse "The tag '$tag' is a lightweight tag, which carries no message for the release's notes. Delete it and push an annotated one: git tag -a $tag."
  fi
  # a signed tag carries its signature at the end of its message, which is no part of the notes
  gh api "repos/$repository/git/tags/$object" --jq '.message' |
    sed '/^-----BEGIN [A-Z ]*SIGNATURE-----$/,$d' >"$file"
  if [ -z "$(tr -d '[:space:]' <"$file")" ]; then
    refuse "The message of the tag '$tag' is empty, and it is what the release publishes as its notes. Delete the tag and push it again with its notes: git tag -a $tag."
  fi
  echo "The release's notes, from the message of the tag $tag:"
  cat "$file"
}

stage() {
  local version="$1" target="$2" expected staged file
  if ! valid_version "$version"; then
    refuse "A release version reads <major>.<minor>.<patch>, but this one is '$version'."
  fi
  mkdir -p "$target/repository"
  target="$(cd "$target" && pwd)"
  if [ -n "$(ls -A "$target/repository")" ]; then
    refuse "$target/repository is not empty; a release is staged into an empty directory."
  fi
  # the full build of the reactor, every test and the coverage gate, and an upload at its end only
  # (the root POM's deployAtEnd), into a local directory; nothing reaches the local Maven
  # repository, where a release version would stand in for the one the registry holds
  (cd "$root" && mvn --batch-mode --no-transfer-progress --update-snapshots \
    -Drevision="$version" \
    -Dmaven.install.skip=true \
    -DaltDeploymentRepository="github::file://$target/repository" \
    deploy)
  expected="$("$root/bin/published-artifacts.sh" "$version")"
  staged="$(published_files "$target/repository")"
  if [ "$staged" != "$expected" ]; then
    refuse "The build of $version did not stage exactly the files a release publishes:
$(diff <(echo "$expected") <(echo "$staged") | sed -n 's/^< /    missing:    /p; s/^> /    unexpected: /p')"
  fi
  echo "Staged $version in $target/repository:"
  while read -r file; do
    echo "    $file"
  done <<<"$staged"
}

dependencies() {
  local staged="$1/repository" snapshots
  # shellcheck disable=SC2016 # the dollars are awk's
  snapshots="$(find "$staged/$group_path" -name '*.pom' -print0 | sort -z |
    xargs -0 awk '
      /<artifactId>/ { artifact = $0; gsub(/^[ \t]*<artifactId>|<\/artifactId>.*$/, "", artifact) }
      /<version>[^<]*-SNAPSHOT<\/version>/ {
        version = $0; gsub(/^[ \t]*<version>|<\/version>.*$/, "", version)
        file = FILENAME; sub(/.*\//, "", file)
        print "    " file ": " artifact " " version
      }')"
  if [ -n "$snapshots" ]; then
    refuse "A release depends on releases only, but the POMs it would publish name snapshots:
$snapshots
A snapshot changes under the release which names it. Set 'spi-for-java.version' and 'adapter-platform.version' of the root pom.xml to a release of VanillaBP on Maven Central, and every other version a POM names to a release."
  fi
  echo "No POM of the release names a snapshot."
}

upload() {
  local staged="$1/repository" url="$2" directory artifact version pom
  if [ ! -d "$staged/$group_path" ]; then
    refuse "$staged holds no staged release; run '$0 stage' first."
  fi
  # the parent first, which every other POM names
  while read -r directory; do
    version="${directory##*/}"
    artifact="$(basename "$(dirname "$directory")")"
    pom="$directory/$artifact-$version.pom"
    local files=(-Dfile="$pom")
    if [ -f "$directory/$artifact-$version.jar" ]; then
      files=(-Dfile="$directory/$artifact-$version.jar" -Dsources="$directory/$artifact-$version-sources.jar")
    fi
    # the deploy plugin of the root POM, with the credentials of server 'github' the settings name
    (cd "$root" && mvn --batch-mode --no-transfer-progress --non-recursive \
      --settings "$root/.github/workflows/settings.xml" \
      deploy:deploy-file "${files[@]}" -DpomFile="$pom" -DrepositoryId=github -Durl="$url")
  done < <(find "$staged/$group_path" -mindepth 2 -maxdepth 2 -type d | sort |
    awk '/zenbpm-vanillabp-adapter-parent\// { print; next } { rest = rest $0 "\n" } END { printf "%s", rest }')
}

rehearse() {
  local staged="$1" target uploaded expected file
  target="$(mktemp -d)"
  # shellcheck disable=SC2064 # the directory is known now
  trap "rm -rf '$target'" EXIT
  # Maven writes its errors to standard output, which goes to the log with them, not to the result
  upload "$staged" "file://$target" >&2
  expected="$(published_files "$staged/repository")"
  uploaded="$(published_files "$target")"
  if [ "$uploaded" != "$expected" ]; then
    refuse "The upload did not carry exactly the staged files into a local repository:
$(diff <(echo "$expected") <(echo "$uploaded") | sed -n 's/^< /    missing:    /p; s/^> /    unexpected: /p')"
  fi
  while read -r file; do
    if ! cmp --silent "$staged/repository/$group_path/${file%%/*}"/*/"${file#*/}" "$target/$group_path/${file%%/*}"/*/"${file#*/}"; then
      refuse "The upload changed $file on its way into a local repository."
    fi
  done <<<"$expected"
  echo "The upload carried the $(echo "$expected" | wc -l) staged files unchanged into a local repository."
}

repository="${GITHUB_REPOSITORY:-pbinitiative/zenbpm-vanillabp-adapter}"
command="${1:-}"
shift || true
case "$command" in
  version) [ "$#" -le 1 ] || usage; version "${1:-}" ;;
  engine) [ "$#" -eq 0 ] || usage; engine ;;
  notes) [ "$#" -eq 2 ] || usage; notes "$1" "$2" ;;
  stage) [ "$#" -eq 2 ] || usage; stage "$1" "$2" ;;
  dependencies) [ "$#" -eq 1 ] || usage; dependencies "$1" ;;
  rehearse) [ "$#" -eq 1 ] || usage; rehearse "$1" ;;
  upload) [ "$#" -eq 2 ] || usage; upload "$1" "$2" ;;
  *) usage ;;
esac
