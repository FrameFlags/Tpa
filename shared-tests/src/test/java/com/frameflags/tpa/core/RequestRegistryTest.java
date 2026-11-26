package com.frameflags.tpa.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestRegistryTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static TpaPose pose(double x) {
        return new TpaPose(x, 64.0, 0.0, 90.0f, 0.0f, "minecraft:overworld");
    }

    @Test
    void selfRequestRejected() {
        RequestRegistry registry = new RequestRegistry();
        assertEquals(TpaResult.SELF_REQUEST,
                registry.request(A, A, "A", TpaRequest.Kind.TO_TARGET, null, 100L, 5));
        assertEquals(0, registry.size());
    }

    @Test
    void duplicatePairRejectedButDifferentTargetsAllowed() {
        RequestRegistry registry = new RequestRegistry();
        assertEquals(TpaResult.OK,
                registry.request(A, B, "B", TpaRequest.Kind.TO_TARGET, null, 100L, 5));
        assertEquals(TpaResult.DUPLICATE,
                registry.request(A, B, "B", TpaRequest.Kind.TO_TARGET, null, 100L, 5));
        assertEquals(TpaResult.OK,
                registry.request(A, C, "C", TpaRequest.Kind.TO_TARGET, null, 100L, 5));
        assertEquals(2, registry.size());
    }

    @Test
    void outgoingLimitEvictsOldest() {
        RequestRegistry registry = new RequestRegistry();
        assertEquals(TpaResult.OK,
                registry.request(A, B, "B", TpaRequest.Kind.TO_TARGET, null, 100L, 2));
        assertEquals(TpaResult.OK,
                registry.request(A, C, "C", TpaRequest.Kind.TO_TARGET, null, 100L, 2));
        assertEquals(TpaResult.OUTGOING_LIMIT,
                registry.request(A, D, "D", TpaRequest.Kind.TO_TARGET, null, 100L, 0));
        assertEquals(TpaResult.OK,
                registry.request(A, D, "D", TpaRequest.Kind.TO_TARGET, null, 100L, 2));
        assertEquals(2, registry.size());
        assertEquals(C, registry.outgoing(A).get(0).target);
        assertEquals(D, registry.outgoing(A).get(1).target);
    }

    @Test
    void hereSnapshotIsImmutable() {
        RequestRegistry registry = new RequestRegistry();
        TpaPose snapshot = pose(10.0);
        assertEquals(TpaResult.OK,
                registry.request(A, B, "B", TpaRequest.Kind.TO_REQUESTER, snapshot, 100L, 5));
        TpaPose moved = pose(999.0);
        assertEquals(10.0, registry.inbound(B).get(0).snapshot.x, 0.0001);
        assertEquals(999.0, moved.x, 0.0001);
        assertTrue(registry.inbound(B).get(0).isHere());
    }

    @Test
    void tickDrainsDueExactlyOnce() {
        RequestRegistry registry = new RequestRegistry();
        registry.request(A, B, "B", TpaRequest.Kind.TO_TARGET, null, 100L, 5);
        registry.request(A, C, "C", TpaRequest.Kind.TO_TARGET, null, 200L, 5);
        assertEquals(0, registry.tick(99L).size());
        List<TpaRequest> due = registry.tick(100L);
        assertEquals(1, due.size());
        assertEquals(B, due.get(0).target);
        assertEquals(0, registry.tick(100L).size());
        assertEquals(1, registry.size());
    }

    @Test
    void takeAndPurgeRemoveBothDirections() {
        RequestRegistry registry = new RequestRegistry();
        registry.request(A, B, "B", TpaRequest.Kind.TO_TARGET, null, 100L, 5);
        registry.request(C, A, "A", TpaRequest.Kind.TO_REQUESTER, pose(1.0), 100L, 5);
        assertEquals(1, registry.takeInbound(B, true).size());
        assertNull(registry.inbound(B).isEmpty() ? null : registry.inbound(B).get(0));
        assertEquals(1, registry.size());
        assertEquals(1, registry.purge(A).size());
        assertEquals(0, registry.size());
        assertNotNull(registry.inbound(B));
    }
}
