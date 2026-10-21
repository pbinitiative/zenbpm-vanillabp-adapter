/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

import com.google.protobuf.ByteString;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a job the job stream received says beyond the engine's message: when its lock lapses,
 * counted from its receipt, and its input variables, decoded as the REST answers decode variables.
 */
@ExtendWith(SuppressOutputExtension.class)
class ReceivedJobTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    @DisplayName("The lock lapses at the moment of receipt plus the subscription's lock, whatever the engine's clock says, and the job's lock_until is read on its own")
    void theLockIsCountedFromTheReceipt() {
        final var lock = Duration.ofMinutes(5);

        assertAll(
                () -> assertEquals(RECEIVED.plus(lock), received(RECEIVED.minusSeconds(3600), "").lockEnd(lock)),
                () -> assertEquals(RECEIVED.plus(lock), received(RECEIVED.plusSeconds(3600), "").lockEnd(lock)),
                () -> assertEquals(Optional.of(RECEIVED.plusSeconds(60)), received(RECEIVED.plusSeconds(60), "")
                        .lockUntil()),
                () -> assertEquals(Optional.empty(), new ReceivedJob(WaitingJob
                        .newBuilder()
                        .build(), RECEIVED).lockUntil()));
    }

    @Test
    @DisplayName("The input variables are decoded as the variables of the REST answers: whole numbers as Long or BigInteger, others as Double, objects and arrays which cannot be changed")
    void theInputVariablesAreDecodedAsTheRestAnswersAre() {
        final var variables = received(RECEIVED, """
                {"amount": 12, "huge": 123456789012345678901234567890, "price": 1.5, "gift": true, "note": null,
                 "address": {"zip": 12345}, "items": [1, "two"]}""").inputVariables();

        assertAll(
                () -> assertEquals(12L, variables.get("amount")),
                () -> assertEquals(new BigInteger("123456789012345678901234567890"), variables.get("huge")),
                () -> assertEquals(1.5, variables.get("price")),
                () -> assertEquals(true, variables.get("gift")),
                () -> assertTrue(variables.containsKey("note")),
                () -> assertEquals(Map.of("zip", 12345L), variables.get("address")),
                () -> assertEquals(List.of(1L, "two"), variables.get("items")),
                () -> assertThrows(UnsupportedOperationException.class, () -> variables.put("other", 1)));
    }

    @Test
    @DisplayName("A job without input variables, or with a JSON null, has none; one carrying no JSON object, or no JSON at all, is refused, naming why without quoting the workflow's data")
    void aJobWithoutAJsonObjectHasNoneOrIsRefused() {
        final var array = assertThrows(IllegalStateException.class, () -> received(RECEIVED, "[\"secret\"]")
                .inputVariables());
        final var broken = assertThrows(IllegalStateException.class, () -> received(RECEIVED, "{\"pin\": secret")
                .inputVariables());

        assertAll(
                () -> assertEquals(Map.of(), received(RECEIVED, "").inputVariables()),
                () -> assertEquals(Map.of(), received(RECEIVED, "null").inputVariables()),
                () -> assertEquals("The variables of a job are JSON, but no JSON object.", array.getMessage()),
                () -> assertEquals(
                        "The variables of a job are no JSON the adapter can read: the engine's answer breaks off or is malformed.",
                        broken.getMessage()),
                () -> assertNull(array.getCause()),
                () -> assertNull(broken.getCause()));
    }

    private static ReceivedJob received(
        final Instant lockUntil,
        final String inputVariables
    ) {
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setLockUntil(lockUntil.toEpochMilli())
                .setInputVariables(ByteString.copyFromUtf8(inputVariables))
                .build(), RECEIVED);
    }

}
