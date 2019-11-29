#!/bin/bash

set -e

ROOT_DIR=$(dirname $(readlink -f $0))/..

LAST_VERSION=$1

REPLACEMENT="perl -pe s|(.*?)(\(?#(\d+)\)?(\s\(#\d+\))?)?$|\*\1\[#\3\]\(https://github.com/akka/akka-http/pull/\3\)|"

echo "#### akka-http-core"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-core | $REPLACEMENT

echo
echo "#### akka-http"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http | $REPLACEMENT

echo
echo "#### akka-http-marshallers"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-marshallers* | $REPLACEMENT

echo
echo "#### akka-http-testkit"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-testkit | $REPLACEMENT

echo
echo "#### docs"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/docs | $REPLACEMENT

echo
echo "#### akka-http2-support"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http2-support | $REPLACEMENT

echo
echo "#### akka-http-caching"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/akka-http-caching | $REPLACEMENT

echo
echo "#### build"
echo
git log --no-merges --reverse --oneline ${LAST_VERSION}.. -- $ROOT_DIR/project $ROOT_DIR/*.sbt | $REPLACEMENT | grep -v Update

