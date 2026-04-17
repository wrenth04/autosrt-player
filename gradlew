#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$APP_HOME/.gradle-dist/gradle-8.7/bin/gradle" "$@"
