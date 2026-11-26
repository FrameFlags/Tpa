package com.frameflags.tpa.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory, insertion-ordered registry of pending requests. No persistence. */
public final class RequestRegistry {
    private final Map<Long, TpaRequest> requests = new LinkedHashMap<Long, TpaRequest>();
    private long nextId = 1L;

    public synchronized TpaResult request(UUID requester, UUID target, String targetName,
                                           TpaRequest.Kind kind, TpaPose snapshot,
                                           long deadlineTick, int maxOutgoing) {
        if (requester == null || target == null || requester.equals(target)) {
            return TpaResult.SELF_REQUEST;
        }
        for (TpaRequest pending : requests.values()) {
            if (pending.requester.equals(requester) && pending.target.equals(target)) {
                return TpaResult.DUPLICATE;
            }
        }
        int outgoing = 0;
        TpaRequest oldestFromRequester = null;
        for (TpaRequest pending : requests.values()) {
            if (pending.requester.equals(requester)) {
                outgoing++;
                if (oldestFromRequester == null) oldestFromRequester = pending;
            }
        }
        int limit = maxOutgoing < 0 ? Integer.MAX_VALUE : maxOutgoing;
        if (outgoing >= limit) {
            if (limit == 0 || oldestFromRequester == null) return TpaResult.OUTGOING_LIMIT;
            requests.remove(oldestFromRequester.id);
        }
        TpaRequest created = new TpaRequest(nextId++, requester, target, targetName,
                kind, snapshot, deadlineTick);
        requests.put(created.id, created);
        return TpaResult.OK;
    }

    public synchronized List<TpaRequest> inbound(UUID target) {
        return filter(target, false, false);
    }

    public synchronized List<TpaRequest> outgoing(UUID requester) {
        return filter(requester, true, false);
    }

    public synchronized TpaRequest get(long id) {
        return requests.get(id);
    }

    /** Removes and returns requests, oldest first. all=true removes every match. */
    public synchronized List<TpaRequest> takeInbound(UUID target, boolean all) {
        return filter(target, false, true, all);
    }

    public synchronized List<TpaRequest> takeOutgoing(UUID requester, boolean all) {
        return filter(requester, true, true, all);
    }

    /** Drains every request whose deadline has passed, exactly once. */
    public synchronized List<TpaRequest> tick(long now) {
        List<TpaRequest> due = new ArrayList<TpaRequest>();
        for (TpaRequest request : requests.values()) {
            if (request.deadlineTick <= now) due.add(request);
        }
        for (TpaRequest request : due) requests.remove(request.id);
        return due;
    }

    /** Removes every request involving this player, in either direction. */
    public synchronized List<TpaRequest> purge(UUID player) {
        List<TpaRequest> removed = new ArrayList<TpaRequest>();
        for (TpaRequest request : requests.values()) {
            if (request.requester.equals(player) || request.target.equals(player)) removed.add(request);
        }
        for (TpaRequest request : removed) requests.remove(request.id);
        return removed;
    }

    public synchronized TpaRequest remove(long id) {
        return requests.remove(id);
    }

    public synchronized void clear() {
        requests.clear();
    }

    public synchronized int size() {
        return requests.size();
    }

    private List<TpaRequest> filter(UUID player, boolean byRequester, boolean remove) {
        return filter(player, byRequester, remove, true);
    }

    private List<TpaRequest> filter(UUID player, boolean byRequester, boolean remove, boolean all) {
        if (player == null) return Collections.emptyList();
        List<TpaRequest> matches = new ArrayList<TpaRequest>();
        for (TpaRequest request : requests.values()) {
            UUID candidate = byRequester ? request.requester : request.target;
            if (player.equals(candidate)) matches.add(request);
            if (!all && !matches.isEmpty()) break;
        }
        if (remove) {
            for (TpaRequest request : matches) requests.remove(request.id);
        }
        return matches;
    }
}
