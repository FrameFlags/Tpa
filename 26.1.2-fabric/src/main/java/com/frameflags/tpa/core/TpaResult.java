package com.frameflags.tpa.core;

/** Result of a request insertion. Player lookup remains adapter-side. */
public enum TpaResult {
    OK,
    SELF_REQUEST,
    DUPLICATE,
    OUTGOING_LIMIT,
    NO_INCOMING,
    NO_OUTGOING
}
