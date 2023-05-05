#!/usr/bin/env bash

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd )

./gradlew -p k2k podspec
./gradlew -p presenter podspec

pod --project-directory=ios install
