#!/bin/sh

if [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -x "./.gradle-dist/gradle-8.7/bin/gradle" ]; then
  exec "./.gradle-dist/gradle-8.7/bin/gradle" "$@"
fi

mkdir -p ./.gradle-dist
if [ ! -f ./.gradle-dist/gradle-8.7-bin.zip ]; then
  curl -L --fail --retry 3 -o ./.gradle-dist/gradle-8.7-bin.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
fi
if [ ! -d ./.gradle-dist/gradle-8.7 ]; then
  unzip -q -o ./.gradle-dist/gradle-8.7-bin.zip -d ./.gradle-dist
fi
exec "./.gradle-dist/gradle-8.7/bin/gradle" "$@"
