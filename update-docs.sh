#!/usr/bin/env bash
cd "$(dirname "$(realpath "$0")")" || exit 1;

rm -rf docs
./gradlew dokka
mv docs/emo/* docs/
rmdir docs/emo
for line in $(find ./docs -name '*.html'); do sed -i 's/..\/style.css/style.css/g' ${line##* .}; done
