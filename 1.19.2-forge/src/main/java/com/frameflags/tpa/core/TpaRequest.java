package com.frameflags.tpa.core;

import java.util.UUID;

/** One pending teleport request. Snapshot is non-null only for TO_REQUESTER (/tpahere). */
public final class TpaRequest {
    public enum Kind { TO_TARGET, TO_REQUESTER }

    public final long id;
    public final UUID requester;
    public final UUID target;
    public final String targetName;
    public final Kind kind;
    public final TpaPose snapshot;
    public final long deadlineTick;

    public TpaRequest(long id, UUID requester, UUID target, String targetName,
                      Kind kind, TpaPose snapshot, long deadlineTick) {
        this.id = id;
        this.requester = requester;
        this.target = target;
        this.targetName = targetName;
        this.kind = kind;
        this.snapshot = snapshot;
        this.deadlineTick = deadlineTick;
    }

    public boolean isHere() {
        return kind == Kind.TO_REQUESTER;
    }
}
