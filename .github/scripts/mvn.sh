#!/bin/bash -e

MAVEN_CLI_ARGS="${MAVEN_CLI_ARGS:-} $@"
VERBOSE=true

while [ $# -gt 0 ]; do
  case "$1" in
    -q | --quiet ) VERBOSE=false; shift ;;
    * ) shift ;;
  esac
done

if [ -n "${VERSION}" ]; then
  MAVEN_CLI_ARGS="--define revision=${VERSION} ${MAVEN_CLI_ARGS}"
  if [ -n "${CHANGE_VERSION}" ]; then
    MAVEN_CLI_ARGS="--define changelist=${CHANGE_VERSION} ${MAVEN_CLI_ARGS}"
  else
    MAVEN_CLI_ARGS="--define changelist= ${MAVEN_CLI_ARGS}"
  fi
else
  if [ -n "${CHANGE_VERSION}" ]; then
    MAVEN_CLI_ARGS="--define changelist=${CHANGE_VERSION} ${MAVEN_CLI_ARGS}"
  fi
fi

MAVEN_PROFILES="${MAVEN_PROFILES:-ci}"
MAVEN_CLI_ARGS="--activate-profiles ${MAVEN_PROFILES} ${MAVEN_CLI_ARGS}"

if [ -n "${WORKSPACE}" ]; then
  MAVEN_CLI_ARGS="--define maven.repo.local=${WORKSPACE}/.m2/repository ${MAVEN_CLI_ARGS}"
fi

MVN_SETTINGS="${MVN_SETTINGS:-$HOME/.m2/settings.xml}"
MAVEN_CLI_ARGS="--settings ${MVN_SETTINGS} ${MAVEN_CLI_ARGS}"

if [ ${VERBOSE} = true ]; then
  MAVEN_CLI_ARGS="--show-version --errors ${MAVEN_CLI_ARGS}"
  set -x
fi

# https://maven.apache.org/configure.html
export MAVEN_OPTS="-Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=192m ${MAVEN_OPTS}"

./mvnw --batch-mode --threads 4 ${MAVEN_CLI_ARGS}
