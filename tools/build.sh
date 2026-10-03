#!/usr/bin/env bash
# Local debug build with JDK 17 (Gradle 8.9 cannot run on newer JDKs). Prints only errors.
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_17:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
./gradlew.bat assembleDebug -q 2>&1 | grep -E "^e:|FAILED|error:" | cut -c1-260
exit ${PIPESTATUS[0]}
