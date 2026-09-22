package io.casehub.claudony.casehub.fleet;

/** Concurrency policy for sessions sharing a working directory. */
public enum WorkingDirPolicy {
    /** Only one active session per directory. Second acquire throws {@link WorkingDirConflictException}. */
    EXCLUSIVE,
    /** Multiple sessions may share a directory; each writes to an isolated output path. */
    SHARED_READ,
    /** Per-session git branch on acquire. Not yet implemented -- throws {@link UnsupportedOperationException}. */
    BRANCH_ISOLATED
}