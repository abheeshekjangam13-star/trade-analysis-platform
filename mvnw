#!/bin/sh
MVNW_REPOURL=https://repo.maven.apache.org/maven2
MVNW_VERSION=3.9.9
MVNW_BIN="${HOME}/.m2/wrapper/dists/apache-maven-${MVNW_VERSION}-bin/apache-maven-${MVNW_VERSION}/bin/mvn"
if [ ! -f "$MVNW_BIN" ]; then
  mkdir -p "$(dirname $MVNW_BIN)/../"
  curl -fsSL "${MVNW_REPOURL}/org/apache/maven/apache-maven/${MVNW_VERSION}/apache-maven-${MVNW_VERSION}-bin.tar.gz" \
    | tar xz -C "$(dirname $MVNW_BIN)/../"
fi
exec "$MVNW_BIN" "$@"
