package badspace.service.partition;

/**
 * The versions of a 3D partition in the retention window: the last version
 * and up to k past versions before it, so at most k + 1 versions. Only the
 * published commits count: a skipped commit number makes no version.
 * <p>
 * A catalog is immutable. The commit makes a new catalog with the new version,
 * and the storage publishes it with a single write, so a reader sees the last
 * version and the window of the same commit.
 */
final class VersionCatalog3 {

    /** k: the past versions kept besides the last one. */
    private final int retainedPastVersions;
    /** The versions in the window, from the oldest to the last one; never empty. */
    private final StorageVersion3[] versions;

    private VersionCatalog3(int retainedPastVersions, StorageVersion3[] versions) {
        this.retainedPastVersions = retainedPastVersions;
        this.versions = versions;
    }

    /**
     * Returns the catalog of a new partition, with only the empty version at
     * commit 0. Fails with IllegalArgumentException if retainedPastVersions is negative.
     */
    static VersionCatalog3 empty(int retainedPastVersions) {
        if (retainedPastVersions < 0) {
            throw new IllegalArgumentException("Negative number of retained past versions: " + retainedPastVersions);
        }
        return new VersionCatalog3(retainedPastVersions, new StorageVersion3[] {StorageVersion3.empty()});
    }

    /**
     * Returns a new catalog with the given version as the last one. If the window
     * is full, the oldest version leaves it. The commit of the version must be
     * greater than the last commit: the storage checks it.
     */
    VersionCatalog3 withVersion(StorageVersion3 version) {
        int kept = Math.min(versions.length, retainedPastVersions);
        StorageVersion3[] next = new StorageVersion3[kept + 1];
        System.arraycopy(versions, versions.length - kept, next, 0, kept);
        next[kept] = version;
        return new VersionCatalog3(retainedPastVersions, next);
    }

    /** Returns the version of the last commit. */
    StorageVersion3 lastVersion() {
        return versions[versions.length - 1];
    }

    /**
     * Returns the version at the given offset from the last one: offset 0 is the last
     * version, offset i the i-th version before it. Returns null if the window has no
     * version at that offset. The offset must not be negative: the node checks it.
     */
    StorageVersion3 previous(int offset) {
        if (offset >= versions.length) {
            return null;
        }
        return versions[versions.length - 1 - offset];
    }

    /**
     * Returns the version in the window with the greatest commit that is not greater
     * than tick, or null if there is none.
     */
    StorageVersion3 atOrBefore(long tick) {
        for (int i = versions.length - 1; i >= 0; i--) {
            if (versions[i].commit() <= tick) {
                return versions[i];
            }
        }
        return null;
    }

    /** Returns the version in the window with the given commit, or null if there is none. */
    StorageVersion3 exact(long commit) {
        StorageVersion3 version = atOrBefore(commit);
        return version != null && version.commit() == commit ? version : null;
    }

    /** Returns k: the number of past versions kept besides the last one. */
    int retainedPastVersions() {
        return retainedPastVersions;
    }
}
