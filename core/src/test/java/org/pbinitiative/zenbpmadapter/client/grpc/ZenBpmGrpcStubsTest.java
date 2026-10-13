/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.grpc.MethodDescriptor.MethodType;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The build generated the job stream's stubs from the pinned proto, and they load on the
 * protobuf runtime the adapter is compiled against.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmGrpcStubsTest {

    /**
     * Reading the method descriptor loads the request and response messages, whose generated
     * code checks the protobuf runtime it finds. So a protoc newer than the runtime of this
     * repository's build fails here, and not first in an application. An application whose own
     * dependencies bring an older runtime than this build's still fails there.
     */
    @Test
    @DisplayName("The stubs offer the engine's job stream as a bidirectional stream")
    void theStubsOfferTheJobStream() {

        final var jobStream = ZenBpmGrpc.getJobStreamMethod();

        assertEquals("grpc.ZenBpm/JobStream", jobStream.getFullMethodName());
        assertEquals(MethodType.BIDI_STREAMING, jobStream.getType());

    }

}
