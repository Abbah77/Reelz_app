#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0
#

##############################################################################
# Helper Functions
##############################################################################

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* )    cygwin=true  ;;
  Darwin* )    darwin=true  ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* )   nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
    fi
fi

# Validate Gradle version (Codemagic compatible)
APP_HOME=$(dirname "$(readlink -f "$0")" 2>/dev/null || dirname "$(realpath "$0")" 2>/dev/null || pwd)

exec "$JAVACMD" \
  -classpath "$CLASSPATH" \
  -Dorg.gradle.appname="$APP_BASE_NAME" \
  org.gradle.wrapper.GradleWrapperMain "$@"
