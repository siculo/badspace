#!/bin/sh
# Writes a description of the benchmark machine and of the file system of a
# save directory, to keep with the results of the persistence benchmarks:
#   ./machine-info.sh SAVE_DIRECTORY [OUTPUT_FILE]
# Default output: machine-info.txt. It does not write the host name.

if [ $# -lt 1 ] || [ ! -d "$1" ]; then
    echo "Usage: $0 SAVE_DIRECTORY [OUTPUT_FILE] (the directory must exist)" >&2
    exit 2
fi
DIR=$(cd "$1" && pwd)
OUT=${2:-machine-info.txt}

# Prints a title and the output of a command, or a note if it fails.
section() {
    title=$1
    shift
    echo "== $title"
    "$@" 2>&1 || echo "(not available: $*)"
    echo
}

governor() {
    cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor | sort | uniq -c
}

disks() {
    source=$(findmnt -n -o SOURCE -T "$DIR" | sed 's/\[.*//')
    # With LVM, RAID or encryption, the inverse tree shows the disks under the device.
    lsblk -s -o NAME,TYPE,SIZE,ROTA,MODEL,TRAN,SCHED "$source"
}

java_version() {
    if [ -n "$JAVA_HOME" ]; then
        "$JAVA_HOME/bin/java" -version
    else
        java -version
    fi
}

{
    echo "Benchmark machine, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Save directory: $DIR"
    echo
    section "Operating system" sh -c 'grep -E "^(PRETTY_NAME|VERSION_ID)=" /etc/os-release; uname -srm'
    section "CPU" sh -c 'lscpu | grep -E "Model name|^CPU\(s\)|Thread|Core|Socket|MHz|L3"'
    section "CPU governor" governor
    section "Memory" free -h
    section "Java" java_version
    section "File system of the save directory" df -hT "$DIR"
    section "Mount of the save directory" findmnt -T "$DIR" -o SOURCE,FSTYPE,OPTIONS
    section "Disks under the save directory" disks
    section "Load at the time of the description" uptime
} > "$OUT"

echo "Description written to $OUT"
