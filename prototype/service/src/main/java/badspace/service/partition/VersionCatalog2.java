package badspace.service.partition;

/**
 * The versions of a 2D partition in the retention window: the last version
 * and up to k past versions before it, so at most k + 1 versions. Only the
 * published commits count: a skipped commit number makes no version.
 * <p>
 * A catalog is immutable. The commit makes a new catalog with the new version,
 * and the storage publishes it with a single write, so a reader sees the last
 * version and the window of the same commit.
 */
final class VersionCatalog2 {

    /** k: the past versions kept besides the last one. */
    private final int retainedPastVersions;
    /** The versions in the window, from the oldest to the last one; never empty. */
    private final StorageVersion2[] versions;

    private VersionCatalog2(int retainedPastVersions, StorageVersion2[] versions) {
        this.retainedPastVersions = retainedPastVersions;
        this.versions = versions;
    }

    /**
     * Returns the catalog of a new partition, with only the empty version at
     * commit 0. Fails with IllegalArgumentException if retainedPastVersions is negative.
     */
    static VersionCatalog2 empty(int retainedPastVersions) {
        if (retainedPastVersions < 0) {
            throw new IllegalArgumentException("Negative number of retained past versions: " + retainedPastVersions);
        }
        return new VersionCatalog2(retainedPastVersions, new StorageVersion2[] {StorageVersion2.empty()});
    }

    /**
     * Returns a new catalog with the given version as the last one. If the window
     * is full, the oldest version leaves it. The commit of the version must be
     * greater than the last commit: the storage checks it.
     */
    VersionCatalog2 withVersion(StorageVersion2 version) {
        int kept = Math.min(versions.length, retainedPastVersions);
        StorageVersion2[] next = new StorageVersion2[kept + 1];
        System.arraycopy(versions, versions.length - kept, next, 0, kept);
        next[kept] = version;
        return new VersionCatalog2(retainedPastVersions, next);
    }

    /**
     * Returns the version that the next {@link #withVersion} makes leave the window,
     * or null if the window is not full.
     */
    StorageVersion2 nextToLeave() {
        return versions.length == retainedPastVersions + 1 ? versions[0] : null;
    }

    /** Returns the version of the last commit. */
    StorageVersion2 lastVersion() {
        return versions[versions.length - 1];
    }

    /**
     * Returns the version at the given offset from the last one: offset 0 is the last
     * version, offset i the i-th version before it. Returns null if the window has no
     * version at that offset. The offset must not be negative: the node checks it.
     */
    StorageVersion2 previous(int offset) {
        if (offset >= versions.length) {
            return null;
        }
        return versions[versions.length - 1 - offset];
    }

    /**
     * Returns the version in the window with the greatest commit that is not greater
     * than tick, or null if there is none.
     */
    StorageVersion2 atOrBefore(long tick) {
        for (int i = versions.length - 1; i >= 0; i--) {
            if (versions[i].commit() <= tick) {
                return versions[i];
            }
        }
        return null;
    }

    /** Returns the version in the window with the given commit, or null if there is none. */
    StorageVersion2 exact(long commit) {
        StorageVersion2 version = atOrBefore(commit);
        return version != null && version.commit() == commit ? version : null;
    }

    /** Returns k: the number of past versions kept besides the last one. */
    int retainedPastVersions() {
        return retainedPastVersions;
    }
}
