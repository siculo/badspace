#!/bin/bash
set -euo pipefail

MODULE_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPOSITORY=$(CDPATH= cd -- "$MODULE_HOME/../.." && pwd -P)

ask() {
    local answer
    printf '%s [%s]: ' "$1" "$2" >&2
    IFS= read -r answer
    REPLY=${answer:-$2}
}

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

ask 'Installation directory' "$(dirname -- "$REPOSITORY")/benchmark-service"
INSTALL_HOME=$(realpath -m -- "$REPLY")
case "$INSTALL_HOME/" in
    "$REPOSITORY/"*) fail 'The installation directory must be outside the repository.' ;;
esac
UPDATE=false
for file in benchmark-server.jar start.sh server.properties; do
    if [ -e "$INSTALL_HOME/$file" ] || [ -L "$INSTALL_HOME/$file" ]; then
        printf 'An existing installation was found in %s. Stop the service before updating.\n' "$INSTALL_HOME"
        ask 'Update the service and keep its configuration and results? (yes/no)' 'no'
        case "$REPLY" in
            yes|y|YES|Y) UPDATE=true ;;
            *) printf 'Installation cancelled.\n'; exit 0 ;;
        esac
        [ -f "$INSTALL_HOME/server.properties" ] || fail 'The existing installation has no server.properties file.'
        break
    fi
done

printf 'Repository: %s\n' "$REPOSITORY"
if [ "$UPDATE" = true ]; then
    ask 'Maven executable (used to build the server)' 'mvn'; MAVEN=$REPLY
else
    ask 'Bind address' '0.0.0.0'; BIND=$REPLY
    ask 'Port' '8080'; PORT=$REPLY
    if [[ ! "$PORT" =~ ^[0-9]{1,5}$ ]] || (( 10#$PORT < 1 || 10#$PORT > 65535 )); then
        fail 'Port must be between 1 and 65535.'
    fi
    ask 'Results directory (relative to the installation directory)' 'results'; RESULTS=$REPLY
    RESULTS_HOME=$(realpath -m -- "$INSTALL_HOME/$RESULTS")
    if [[ "$RESULTS" = /* ]]; then
        RESULTS_HOME=$(realpath -m -- "$RESULTS")
    fi
    case "$RESULTS_HOME/" in
        "$REPOSITORY/"*) fail 'Results must be outside the repository.' ;;
    esac
    case "$REPOSITORY/" in
        "$RESULTS_HOME/"*) fail 'Results must not contain the repository.' ;;
    esac
    ask 'Git executable' 'git'; GIT=$REPLY
    ask 'Maven executable (also used to build the server)' 'mvn'; MAVEN=$REPLY
    ask 'Benchmark Java executable (empty uses the service runtime)' ''; JAVA=$REPLY
    ask 'API bearer token (empty disables authentication)' ''; TOKEN=$REPLY
fi

# Keep executable paths valid when the service runs from another directory.
for name in GIT MAVEN JAVA; do
    value=${!name-}
    if [[ "$value" = */* && "$value" != /* ]]; then
        printf -v "$name" '%s' "$(realpath -m -- "$value")"
    fi
done

"$MAVEN" -f "$REPOSITORY/prototype/pom.xml" -pl benchmark-server -am clean package

# Escape values for Java Properties, including backslashes and leading spaces.
property() {
    local value=$2
    value=${value//\\/\\\\}
    value=${value//$'\t'/\\t}
    value=${value//$'\r'/\\r}
    value=${value//$'\n'/\\n}
    value=${value// /\\ }
    printf '%s=%s\n' "$1" "$value"
}

mkdir -p -- "$INSTALL_HOME"
install -m 644 -- "$MODULE_HOME/target/benchmark-server.jar" "$INSTALL_HOME/benchmark-server.jar"
install -m 755 -- "$MODULE_HOME/start.sh" "$INSTALL_HOME/start.sh"
if [ "$UPDATE" = false ]; then
    (
        umask 077
        {
            property bind "$BIND"
            property port "$PORT"
            property repository "$REPOSITORY"
            property results "$RESULTS_HOME"
            property git "$GIT"
            property maven "$MAVEN"
            property java "$JAVA"
            property token "$TOKEN"
        } > "$INSTALL_HOME/server.properties"
    )
fi
if [ "$UPDATE" = true ]; then
    printf 'Updated in %s\n' "$INSTALL_HOME"
else
    printf 'Installed in %s\n' "$INSTALL_HOME"
fi
printf 'Start the service with: "%s/start.sh"\n' "$INSTALL_HOME"
