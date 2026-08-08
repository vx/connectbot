#!/bin/sh
set -e

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

DEFAULT_JVM_OPTS=''

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if [ ! -x "$JAVACMD" ]; then
  echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
  exit 1
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
