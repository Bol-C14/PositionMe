package com.openpositioning.PositionMe.data.local.session;

import java.io.IOException;

/** Package-visible test seam for simulating process failure at durable publication boundaries. */
interface SessionFailureInjector {
    void hit(String boundary) throws IOException;
}
