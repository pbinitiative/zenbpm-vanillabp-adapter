/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Each answer of {@link ZenBpmValueTypes} held against the pinned engine: a value of each type is
 * sent the way a start sends it and read back. A type which survives comes back as the same text,
 * the same boolean or the same number, which is what the platform compares before it hands a number
 * to a handler in the declared type; a value of a type which is changed does not.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmValueTypesIT {

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient client;

    private static Map<String, Object> readBack;

    @BeforeAll
    static void sendAValueOfEveryType() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        client = new ZenBpmRestClient("value-types", configuration);
        client.deployBpmn(contractTask());
        final var sent = new LinkedHashMap<String, Object>();
        values().forEach(arguments -> sent.put(((Class<?>) arguments.get()[0]).getName(), arguments.get()[1]));
        sent.put(StringBuilder.class.getName(), new StringBuilder("a text built"));
        final var started = client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withBusinessKey("order-%s".formatted(UUID.randomUUID()))
                .withVariables(sent));
        readBack = client
                .instance(started.key())
                .orElseThrow()
                .variables();
    }

    @AfterAll
    static void closeTheClient() {
        client.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("A value comes back from the engine as the answer of its type says, in both directions")
    void aValueComesBackAsItsTypeIsAnswered(
        final Class<?> type,
        final Object sent
    ) {
        final var back = readBack.get(type.getName());
        final var survives = sameValue(sent, back);

        assertAll(
                () -> assertEquals(survives ? ValueTypeVerdict.Kind.SURVIVES : ValueTypeVerdict.Kind.CHANGED,
                        ZenBpmValueTypes
                                .verdictFor(type, ValueDirection.TO_BPMS)
                                .kind(),
                        "sent %s, read back %s".formatted(sent, back)),
                () -> assertEquals(ZenBpmValueTypes
                        .verdictFor(type, ValueDirection.TO_BPMS)
                        .kind(),
                        ZenBpmValueTypes
                                .verdictFor(type, ValueDirection.FROM_BPMS)
                                .kind()));
    }

    @Test
    @DisplayName("A whole number sent with a fraction comes back without it, as the same number")
    void aWholeNumberWithAFractionComesBackAsTheSameNumber() {
        final var back = readBack.get(Double.class.getName());

        assertAll(
                () -> assertEquals(Long.class, back.getClass()),
                () -> assertEquals(0, new BigDecimal("1000.0").compareTo(new BigDecimal(back.toString()))));
    }

    @Test
    @DisplayName("A text of another type than String reaches the engine as its text and comes back as a String")
    void aTextOfAnotherTypeComesBackAsAString() {
        assertAll(
                () -> assertEquals("a text built", readBack.get(StringBuilder.class.getName())),
                () -> assertEquals(ValueTypeVerdict.Kind.SURVIVES, ZenBpmValueTypes
                        .verdictFor(StringBuilder.class, ValueDirection.TO_BPMS)
                        .kind()),
                () -> assertEquals(ValueTypeVerdict.Kind.CHANGED, ZenBpmValueTypes
                        .verdictFor(StringBuilder.class, ValueDirection.FROM_BPMS)
                        .kind()));
    }

    /**
     * A value of every type whose answer is not "cannot say", the primitives represented by their
     * wrappers. A date and an instant are sent as the text the platform writes for them.
     */
    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of(String.class, "a text with ü and \"quotes\""),
                Arguments.of(Boolean.class, Boolean.TRUE),
                Arguments.of(Integer.class, Integer.MIN_VALUE),
                Arguments.of(Short.class, Short.MAX_VALUE),
                Arguments.of(Byte.class, Byte.MIN_VALUE),
                Arguments.of(Double.class, 1000.0),
                Arguments.of(Float.class, 0.1f),
                Arguments.of(ValueDirection.class, ValueDirection.FROM_BPMS.name()),
                Arguments.of(Instant.class, Instant
                        .parse("2026-10-17T14:30:25.123456789Z")
                        .toString()),
                Arguments.of(Date.class, new Date(1_760_711_425_123L)
                        .toInstant()
                        .toString()),
                Arguments.of(Long.class, (1L << 53) + 1),
                Arguments.of(BigInteger.class, BigInteger.TWO
                        .pow(60)
                        .add(BigInteger.ONE)),
                Arguments.of(BigDecimal.class, new BigDecimal("12345678901234567.89")),
                Arguments.of(Character.class, 'c'));
    }

    // a number is the same where its decimal forms are the same number, as the platform compares
    private static boolean sameValue(
        final Object sent,
        final Object back
    ) {
        if ((sent instanceof Number) && (back instanceof Number)) {
            return new BigDecimal(sent.toString()).compareTo(new BigDecimal(back.toString())) == 0;
        }
        return sent.equals(back);
    }

    private static byte[] contractTask() {
        try (var resource = ZenBpmValueTypesIT.class.getResourceAsStream("/contract/contract-task.bpmn")) {
            return resource.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
