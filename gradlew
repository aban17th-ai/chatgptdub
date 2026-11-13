
#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
    echo "Gradle wrapper JAR not found!"
    exit 1
fi

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
