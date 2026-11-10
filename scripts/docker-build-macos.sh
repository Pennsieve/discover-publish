#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
SOURCE_JAR_FILE=`/bin/ls -1 $PROJECT_ROOT/discover-publish/target/scala-2.13/multipart-uploader-assembly-bootstrap-*.jar`
TARGET_JAR_FILE=csv-s3-ops.jar
echo "SCRIPT_DIR: $SCRIPT_DIR"
echo "PROJECT_ROOT: $PROJECT_ROOT"
echo "SOURCE_JAR_FILE: $SOURCE_JAR_FILE"
echo "TARGET_JAR_FILE: $TARGET_JAR_FILE"
cp $SOURCE_JAR_FILE $TARGET_JAR_FILE

docker build \
    -f Dockerfile.macos.csv-s3-ops \
    -t "pennsieve/s3-ops-machine-macos:latest" \
    .

