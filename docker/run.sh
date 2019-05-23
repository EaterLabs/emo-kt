#!/usr/bin/env bash
cd "$(dirname "$(realpath "$0")")";

docker build -t emo-builder .
docker run -v "$PWD/..":/app -ti -w /app --rm emo-builder ./gradlew -i shadowJar
