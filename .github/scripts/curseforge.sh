#!/usr/bin/env bash
# Uploads one jar per node to CurseForge.
#
# Usage: curseforge.sh <tag> <jar dir> <changelog file> [--dry-run]
# Needs CURSEFORGE_TOKEN and CURSEFORGE_PROJECT_ID. With --dry-run the game versions
# are still resolved, so a bad version name fails here too, but nothing is uploaded.
#
# Everything a file declares comes from versions/<node>/gradle.properties: the
# Minecraft version, the loader from the node name, the Java version, and the
# renderers and config mods the node builds against as optional dependencies.
set -euo pipefail

tag="$1"
jars="$2"
changelog="$3"
dry_run="${4:-}"
version="${tag#v}"
api="https://minecraft.curseforge.com/api"

: "${CURSEFORGE_TOKEN:?CURSEFORGE_TOKEN is not set}"
: "${CURSEFORGE_PROJECT_ID:?CURSEFORGE_PROJECT_ID is not set}"

case "$version" in
  *alpha*) release_type=alpha ;;
  *beta* | *rc* | *pre*) release_type=beta ;;
  *) release_type=release ;;
esac

get() {
  curl --fail-with-body -sS -H "X-Api-Token: ${CURSEFORGE_TOKEN}" "${api}/$1"
}

# Every file is tagged with game version IDs, and CurseForge files Minecraft versions,
# loaders, Java versions and environments all under "game versions" of different types.
types=$(get game/version-types)
versions=$(get game/versions)

# Prints the ID of the version called <name> among the types whose slug matches <regex>.
version_id() {
  local type_ids
  type_ids=$(jq -c --arg re "$2" '[.[] | select(.slug | test($re)) | .id]' <<<"$types")
  jq -r --arg name "$1" --argjson types "$type_ids" \
    'first(.[] | select(.name == $name and (.gameVersionTypeID as $t | $types | any(. == $t)))) | .id // empty' \
    <<<"$versions"
}

prop() {
  awk -v key="$2" 'index($0, key "=") == 1 { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' \
    "versions/$1/gradle.properties"
}

nodes=$(sed -n 's/.*version("\([^"]*\)".*/\1/p' settings.gradle.kts)
for node in $nodes; do
  minecraft="${node%-*}"
  loader="${node##*-}"
  jar="${jars}/better-blending-${node}-${version}.jar"
  [ -f "$jar" ] || { echo "::error::Missing ${jar}"; exit 1; }

  case "$loader" in
    fabric) loader_name=Fabric ;;
    forge) loader_name=Forge ;;
    neoforge) loader_name=NeoForge ;;
    *) echo "::error::Unknown loader ${loader}"; exit 1 ;;
  esac

  minecraft_id=$(version_id "$minecraft" '^minecraft-')
  loader_id=$(version_id "$loader_name" '^modloader$')
  [ -n "$minecraft_id" ] || { echo "::error::CurseForge has no Minecraft version ${minecraft}"; exit 1; }
  [ -n "$loader_id" ] || { echo "::error::CurseForge has no loader ${loader_name}"; exit 1; }
  ids=("$minecraft_id" "$loader_id")

  # Java and environment tags are nice to have, so a missing one only warns.
  java="Java $(prop "$node" java.version)"
  java_id=$(version_id "$java" '^java$')
  client_id=$(version_id Client '^environment')
  [ -n "$java_id" ] && ids+=("$java_id") || echo "::warning::CurseForge has no ${java}; ${node} is uploaded without it"
  [ -n "$client_id" ] && ids+=("$client_id") || echo "::warning::CurseForge has no Client environment; ${node} is uploaded without it"

  # CurseForge slugs, which differ from the Modrinth ones in gradle.properties for Iris.
  required=()
  optional=(cloth-config)
  [ "$loader" = fabric ] && required+=(fabric-api)
  [ -n "$(prop "$node" modmenu.version)" ] && optional+=(modmenu)
  [ -n "$(prop "$node" sodium.version)" ] && optional+=(sodium)
  # 1.21.1 Fabric compiles against Embeddium too, but Embeddium only runs on (Neo)Forge.
  [ "$loader" != fabric ] && [ -n "$(prop "$node" embeddium.version)" ] && optional+=(embeddium)
  [ -n "$(prop "$node" rubidium.version)" ] && optional+=(rubidium)
  # Only iris.version names a runnable release; iris.compile.version does not.
  if [ -n "$(prop "$node" iris.version)" ]; then
    case "$(prop "$node" iris.slug)" in
      oculus) optional+=(oculus) ;;
      *) optional+=(irisshaders) ;;
    esac
  fi

  metadata="${RUNNER_TEMP:-/tmp}/curseforge-${node}.json"
  jq -n \
    --rawfile changelog "$changelog" \
    --arg name "Better Blending ${version} (${minecraft} ${loader_name})" \
    --arg type "$release_type" \
    --argjson ids "$(printf '%s\n' "${ids[@]}" | jq -s 'map(tonumber)')" \
    --argjson required "$(printf '%s\n' "${required[@]}" | jq -R -s 'split("\n") | map(select(length > 0))')" \
    --argjson optional "$(printf '%s\n' "${optional[@]}" | jq -R -s 'split("\n") | map(select(length > 0))')" \
    '{
      changelog: $changelog,
      changelogType: "markdown",
      displayName: $name,
      gameVersions: $ids,
      releaseType: $type,
      relations: {
        projects: (
          [$required[] | {slug: ., type: "requiredDependency"}]
          + [$optional[] | {slug: ., type: "optionalDependency"}]
        )
      }
    }' >"$metadata"

  echo "::group::${node}: $(basename "$jar")"
  jq . "$metadata"
  if [ "$dry_run" = --dry-run ]; then
    echo "Dry run, not uploaded."
  else
    curl --fail-with-body -sS \
      -H "X-Api-Token: ${CURSEFORGE_TOKEN}" \
      -F "metadata=<${metadata};type=application/json" \
      -F "file=@${jar}" \
      "${api}/projects/${CURSEFORGE_PROJECT_ID}/upload-file"
    echo
  fi
  echo "::endgroup::"
done
