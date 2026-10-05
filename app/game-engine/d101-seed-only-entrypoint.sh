#!/bin/sh
# Candidate worker only. Its image must contain the pinned /app/app.jar and
# the one approved ServiceLoader provider; ordinary engine startup is forbidden.
set -eu
if [ "$#" -ne 0 ]; then
    exit 78
fi
unset LOADER_MAIN LOADER_PATH LOADER_ARGS LOADER_HOME LOADER_CONFIG_NAME
unset LOADER_CONFIG_LOCATION LOADER_SYSTEM
exec java -Dloader.main=opensamguk.engine.boot.D101SeedOnlyCli \
    -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
    --d101-seed-only-v1
