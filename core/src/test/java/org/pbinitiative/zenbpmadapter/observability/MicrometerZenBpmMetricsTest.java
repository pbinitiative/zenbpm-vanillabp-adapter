/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.observability;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter adds to the meters the platform publishes for every BPMS: the names, the kinds
 * and the tag of the meters, which the README's section "Metrics" lists and this test keeps in step
 * with the code; that the counters of an adapter id exist at zero from its registration on; that
 * nothing counted before a registry is bound is lost, and every bound registry reads the same
 * number; and that two adapter ids keep their numbers apart. Where each counter moves is shown at
 * the place its event happens, in the tests of the dispatcher, the lock renewal, the handlers and
 * the user-task poller.
 */
@ExtendWith(SuppressOutputExtension.class)
class MicrometerZenBpmMetricsTest {

    private static final String README_SECTION = "## Metrics";

    @Test
    @DisplayName("An adapter id registers every meter of the README's table, each of the kind and with the tag the table names, the counters at zero")
    void anAdapterIdRegistersTheMetersOfTheReadme() throws IOException, URISyntaxException {
        final var registry = new SimpleMeterRegistry();
        final var metrics = new MicrometerZenBpmMetrics();
        metrics.bindTo(registry);

        metrics.registerAdapter("orders", () -> 0, () -> 0, () -> 4);

        final var registered = new TreeMap<String, String>();
        registry
                .getMeters()
                .forEach(meter -> registered.put(meter
                        .getId()
                        .getName(), describe(meter)));
        final var readme = new TreeMap<String, String>();
        rowsOfTheReadmeTable().forEach(row -> readme.put(row.getFirst(), "%s %s".formatted(row.get(1), row.get(2))));
        assertAll(
                () -> assertEquals(readme, registered),
                () -> assertEquals(constantsOfTheMeters(), registered.keySet(),
                        "every name ZenBpmMetrics declares is registered, and no other one"),
                () -> assertTrue(registry
                        .getMeters()
                        .stream()
                        .filter(meter -> meter
                                .getId()
                                .getType() == Meter.Type.COUNTER)
                        .allMatch(meter -> registry
                                .get(meter
                                        .getId()
                                        .getName())
                                .functionCounter()
                                .count() == 0),
                        "every counter starts at zero"));
    }

    @Test
    @DisplayName("Each counter counts its own event for its own adapter id: two adapter ids publish two sets which differ in the tag only")
    void twoAdapterIdsKeepTheirNumbersApart() {
        final var registry = new SimpleMeterRegistry();
        final var metrics = new MicrometerZenBpmMetrics();
        metrics.bindTo(registry);
        metrics.registerAdapter("zenbpm-old", () -> 3, () -> 1, () -> 0);
        metrics.registerAdapter("zenbpm-new", () -> 0, () -> 0, () -> 2);

        metrics.jobReceived("zenbpm-old");
        metrics.jobReceived("zenbpm-old");
        metrics.jobRedelivered("zenbpm-old");
        metrics.jobQueued("zenbpm-old");
        metrics.jobCompleted("zenbpm-old");
        metrics.jobFailed("zenbpm-old");
        metrics.lockRenewed("zenbpm-old");
        metrics.lockLost("zenbpm-old");
        metrics.userTaskPoll("zenbpm-old");
        metrics.jobReceived("zenbpm-new");

        assertAll(
                () -> assertEquals(2, counted(registry, ZenBpmMetrics.JOBS_RECEIVED, "zenbpm-old")),
                () -> assertEquals(1, counted(registry, ZenBpmMetrics.JOBS_RECEIVED, "zenbpm-new")),
                () -> assertEquals(Map.of(
                        ZenBpmMetrics.JOBS_REDELIVERED, 1L,
                        ZenBpmMetrics.JOBS_QUEUED, 1L,
                        ZenBpmMetrics.JOBS_COMPLETED, 1L,
                        ZenBpmMetrics.JOBS_FAILED, 1L,
                        ZenBpmMetrics.LOCK_RENEWED, 1L,
                        ZenBpmMetrics.LOCK_LOST, 1L,
                        ZenBpmMetrics.USER_TASK_POLLS, 1L,
                        ZenBpmMetrics.STREAM_RECONNECTS, 3L), countsOtherThanReceived(registry, "zenbpm-old")),
                () -> assertEquals(Map.of(
                        ZenBpmMetrics.JOBS_REDELIVERED, 0L,
                        ZenBpmMetrics.JOBS_QUEUED, 0L,
                        ZenBpmMetrics.JOBS_COMPLETED, 0L,
                        ZenBpmMetrics.JOBS_FAILED, 0L,
                        ZenBpmMetrics.LOCK_RENEWED, 0L,
                        ZenBpmMetrics.LOCK_LOST, 0L,
                        ZenBpmMetrics.USER_TASK_POLLS, 0L,
                        ZenBpmMetrics.STREAM_RECONNECTS, 0L), countsOtherThanReceived(registry, "zenbpm-new")),
                () -> assertEquals(1.0, gauged(registry, ZenBpmMetrics.SLOTS_BUSY, "zenbpm-old")),
                () -> assertEquals(2.0, gauged(registry, ZenBpmMetrics.SLOTS_FREE, "zenbpm-new")),
                () -> assertEquals(22, registry
                        .getMeters()
                        .size(), "eleven meters per adapter id"));
    }

    @Test
    @DisplayName("Nothing counted before a registry is bound is lost, a registry bound later reads every count, and two registries read the same number")
    void whatIsCountedBeforeTheRegistryIsKept() {
        final var metrics = new MicrometerZenBpmMetrics();
        // a count for an adapter id whose deployment service did not register it yet
        metrics.jobReceived("orders");
        metrics.registerAdapter("orders", () -> 0, () -> 0, () -> 1);
        metrics.jobReceived("orders");

        final var first = new SimpleMeterRegistry();
        metrics.bindTo(first);
        metrics.jobReceived("orders");
        final var second = new SimpleMeterRegistry();
        metrics.bindTo(second);
        // a count for an adapter id which never registers: the counter is made on its first count
        metrics.jobFailed("elsewhere");

        assertAll(
                () -> assertEquals(3, counted(first, ZenBpmMetrics.JOBS_RECEIVED, "orders")),
                () -> assertEquals(3, counted(second, ZenBpmMetrics.JOBS_RECEIVED, "orders")),
                () -> assertEquals(1, counted(first, ZenBpmMetrics.JOBS_FAILED, "elsewhere")),
                () -> assertEquals(1, counted(second, ZenBpmMetrics.JOBS_FAILED, "elsewhere")),
                () -> assertEquals(1.0, gauged(second, ZenBpmMetrics.SLOTS_FREE, "orders")));
    }

    @Test
    @DisplayName("The slot gauges and the reconnects counter read the values where they are kept, at every collection")
    void theReadValuesAreReadAtEveryCollection() {
        final var registry = new SimpleMeterRegistry();
        final var metrics = new MicrometerZenBpmMetrics();
        final var reconnects = new AtomicLong();
        final var busy = new AtomicInteger();
        final var free = new AtomicInteger(4);
        metrics.registerAdapter("orders", reconnects::get, busy::get, free::get);
        metrics.bindTo(registry);
        final var atFirst = List.of(counted(registry, ZenBpmMetrics.STREAM_RECONNECTS, "orders"), (long) gauged(
                registry, ZenBpmMetrics.SLOTS_BUSY, "orders"),
                (long) gauged(registry, ZenBpmMetrics.SLOTS_FREE,
                        "orders"));

        reconnects.set(5);
        busy.set(4);
        free.set(0);

        assertAll(
                () -> assertEquals(List.of(0L, 0L, 4L), atFirst),
                () -> assertEquals(5, counted(registry, ZenBpmMetrics.STREAM_RECONNECTS, "orders"),
                        "a stream which keeps trying again while no job arrives"),
                () -> assertEquals(4.0, gauged(registry, ZenBpmMetrics.SLOTS_BUSY, "orders")),
                () -> assertEquals(0.0, gauged(registry, ZenBpmMetrics.SLOTS_FREE, "orders"),
                        "every slot taken: the jobs which arrive now wait"));
    }

    @Test
    @DisplayName("A second registration of an adapter id changes nothing: every registry, bound before or after it, reads the values of the first")
    void aSecondRegistrationOfAnAdapterIdChangesNothing() {
        final var before = new SimpleMeterRegistry();
        final var metrics = new MicrometerZenBpmMetrics();
        metrics.bindTo(before);
        final var free = new AtomicInteger(4);
        metrics.registerAdapter("orders", () -> 0, () -> 0, free::get);
        final var otherFree = new AtomicInteger(9);
        metrics.registerAdapter("orders", () -> 0, () -> 0, otherFree::get);
        final var after = new SimpleMeterRegistry();
        metrics.bindTo(after);

        assertAll(
                () -> assertEquals(4.0, gauged(before, ZenBpmMetrics.SLOTS_FREE, "orders")),
                () -> assertEquals(4.0, gauged(after, ZenBpmMetrics.SLOTS_FREE, "orders")));
    }

    @Test
    @DisplayName("Without a metrics backend nothing is measured, and nothing throws")
    void withoutABackendNothingIsMeasured() {
        assertDoesNotThrow(() -> {
            final var metrics = ZenBpmMetrics.NONE;
            metrics.registerAdapter("orders", () -> 0, () -> 0, () -> 1);
            metrics.jobReceived("orders");
            metrics.jobRedelivered("orders");
            metrics.jobQueued("orders");
            metrics.jobCompleted("orders");
            metrics.jobFailed("orders");
            metrics.lockRenewed("orders");
            metrics.lockLost("orders");
            metrics.userTaskPoll("orders");
        });
    }

    private static String describe(final Meter meter) {
        final var kind = meter
                .getId()
                .getType() == Meter.Type.GAUGE ? "gauge" : "counter";
        return "%s %s".formatted(kind, meter
                .getId()
                .getTags()
                .stream()
                .map(Tag::getKey)
                .map("`%s`"::formatted)
                .collect(Collectors.joining(", ")));
    }

    private static Map<String, Long> countsOtherThanReceived(
        final MeterRegistry registry,
        final String adapterId
    ) {
        return Stream
                .of(ZenBpmMetrics.JOBS_REDELIVERED, ZenBpmMetrics.JOBS_QUEUED, ZenBpmMetrics.JOBS_COMPLETED,
                        ZenBpmMetrics.JOBS_FAILED, ZenBpmMetrics.LOCK_RENEWED, ZenBpmMetrics.LOCK_LOST,
                        ZenBpmMetrics.USER_TASK_POLLS, ZenBpmMetrics.STREAM_RECONNECTS)
                .collect(Collectors.toMap(name -> name, name -> counted(registry, name, adapterId)));
    }

    private static long counted(
        final MeterRegistry registry,
        final String meterName,
        final String adapterId
    ) {
        return (long) registry
                .get(meterName)
                .tag(ZenBpmMetrics.TAG_ADAPTER, adapterId)
                .functionCounter()
                .count();
    }

    private static double gauged(
        final MeterRegistry registry,
        final String meterName,
        final String adapterId
    ) {
        return registry
                .get(meterName)
                .tag(ZenBpmMetrics.TAG_ADAPTER, adapterId)
                .gauge()
                .value();
    }

    // every constant of the interface whose value names a meter of this adapter
    private static Set<String> constantsOfTheMeters() {
        return Stream
                .of(ZenBpmMetrics.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && (field.getType() == String.class))
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (final IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(value -> value.startsWith("vanillabp.zenbpm."))
                .collect(Collectors.toSet());
    }

    /**
     * The first three cells of every row of the README's table, the meter's name without its
     * backticks, its kind and its tags.
     */
    private static List<List<String>> rowsOfTheReadmeTable() throws IOException, URISyntaxException {
        final var readme = Files.readString(repositoryRoot().resolve("README.md"));
        final var start = readme.indexOf("%s\n".formatted(README_SECTION));
        assertTrue(start >= 0, "README.md has no section '%s' any more".formatted(README_SECTION));
        final var rows = new ArrayList<List<String>>();
        final var named = new HashSet<String>();
        final var table = readme
                .substring(start)
                .lines()
                .dropWhile(line -> !line.startsWith("|"))
                .takeWhile(line -> line.startsWith("|"))
                // the header and the line below it
                .skip(2)
                .toList();
        for (final var line : table) {
            final var cells = line.split("\\|");
            final var name = cells[1]
                    .strip()
                    .replace("`", "");
            assertTrue(named.add(name), "README.md names the meter '%s' twice".formatted(name));
            rows.add(List.of(name, cells[2].strip(), cells[3].strip()));
        }
        assertFalse(rows.isEmpty(), "The section '%s' of README.md has no table".formatted(README_SECTION));
        return rows;
    }

    // the test classes are in <module>/target/test-classes, and the module in the repository root
    private static Path repositoryRoot() throws URISyntaxException {
        return Path
                .of(MicrometerZenBpmMetricsTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .getParent()
                .getParent()
                .getParent();
    }

}
