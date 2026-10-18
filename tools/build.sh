#!/usr/bin/env bash
# Local debug build with JDK 17 (Gradle 8.9 cannot run on newer JDKs). Prints only errors (and Gradle's "What went wrong").
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_17:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
./gradlew.bat assembleDebug -q 2>&1 | awk '/^e:|FAILED|error:/ { print substr($0, 1, 260); next } /What went wrong/ { n = 4 } n > 0 { print substr($0, 1, 260); n-- }'
exit ${PIPESTATUS[0]}
