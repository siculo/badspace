#!/bin/sh
# Sets up what coding agents need in this repository (Linux and macOS).
# You can run it again at any time: it only fixes what is missing or wrong.
set -eu
cd "$(dirname "$0")"

# Creates the symbolic link $1 that points to $2 (relative to the link).
link_dir() {
    link=$1
    target=$2
    if [ -L "$link" ] && [ "$(readlink "$link")" = "$target" ]; then
        echo "$link already points to $target"
        return
    fi
    if [ -d "$link" ] && [ ! -L "$link" ]; then
        echo "error: $link is a directory, not a link" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$link")"
    ln -sfn "$target" "$link"
    echo "$link now points to $target"
}

# Claude Code reads the skills from .claude/skills.
link_dir .claude/skills ../.agents/skills
