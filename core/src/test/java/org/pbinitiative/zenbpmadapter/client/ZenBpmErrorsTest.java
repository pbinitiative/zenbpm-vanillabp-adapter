/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How a failed call of the engine is read. The classification is a function of the failure
 * alone, so every case is a failure built here; {@link ZenBpmErrorsIT} has the pinned engine
 * answer the statuses it can be made to answer.
 * <p>
 * The cases are the table of the README's section "Which phase-two failures are repeated", row by
 * row, and {@link #theReadmeAndThisTestNameTheSameRows()} keeps the two in step.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmErrorsTest {

    private static final String ADAPTER_ID = "orders";

    private static final String README_SECTION = "## Which phase-two failures are repeated";

    @ParameterizedTest(name = "{0} [{1}]: {2}")
    @MethodSource("everyRowOfTheReadmeTable")
    @DisplayName("Each failure of the README's table is read as the table says, plain and wrapped")
    void eachFailureIsReadAsTheTableSays(
        final String row,
        final ReadAs readAs,
        final Throwable failure
    ) {
        final var expected = readAs.answers();
        assertEquals(expected, answersTo(failure), "plain");
        assertEquals(expected, answersTo(new CompletionException(failure)), "in a CompletionException");
        assertEquals(expected, answersTo(new RuntimeException("dispatching failed", failure)), "in a RuntimeException");
        assertEquals(expected, answersTo(new CompletionException(new RuntimeException("dispatching failed", failure))),
                "wrapped twice");
    }

    @Test
    @DisplayName("The README's table and this test name the same rows, each read the same way")
    void theReadmeAndThisTestNameTheSameRows() throws IOException, URISyntaxException {
        final var tested = new LinkedHashMap<String, String>();
        everyRowOfTheReadmeTable().forEach(arguments -> {
            final var row = (String) arguments.get()[0];
            final var readAs = ((ReadAs) arguments.get()[1]).label;
            final var before = tested.putIfAbsent(row, readAs);
            assertTrue((before == null) || before.equals(readAs), "The row '%s' is read as both '%s' and '%s'"
                    .formatted(row, before, readAs));
        });

        assertEquals(tested
                .entrySet()
                .stream()
                .map(row -> Map.entry(row.getKey(), row.getValue()))
                .toList(), rowsOfTheReadmeTable());
    }

    @Test
    @DisplayName("Nothing is read from a message: neither the engine's nor that of a failure without a status")
    void nothingIsReadFromAMessage() {
        final var completion = "/v1/jobs/42/complete";
        final var reworded = new ZenBpmApiException(ADAPTER_ID, 500, "NOT_FOUND", "job 42 not found", "POST", completion);
        final var wordsAlone = new IllegalStateException("POST /v1/messages was answered with status 404");

        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(reworded));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(wordsAlone));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(new IOException("Connection reset")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(null));
    }

    @Test
    @DisplayName("The first exception of the client answers alone, whatever the HTTP client reported beneath it")
    void theFirstExceptionOfTheClientAnswersAlone() {
        // the client's answer to a status the call does not expect, whose body then stalled
        final var stalledRefusal = refusal(404, "POST", "/v1/messages");
        stalledRefusal.initCause(new HttpTimeoutException("request timed out"));
        final var refusedAboveATimeout = refusal(400, "POST", "/v1/process-instances");
        refusedAboveATimeout.initCause(new ConnectException("Connection refused"));

        assertEquals(EnumSet.of(ReadAs.NOBODY_WAITS, ReadAs.REPEATABLE), answersTo(stalledRefusal));
        assertEquals(EnumSet.of(ReadAs.PERMANENT), answersTo(new RuntimeException(refusedAboveATimeout)));
    }

    @Test
    @DisplayName("A failure this class reads without the client, wrapped around the client's exception, does not hide it")
    void aFailureWrappedAroundTheClientsExceptionDoesNotHideIt() {
        final var timeoutAroundARefusal = new HttpTimeoutException("request timed out");
        timeoutAroundARefusal.initCause(refusal(400, "POST", "/v1/process-instances"));
        final var refusedConnectionAroundNobodyWaits = new ConnectException("Connection refused");
        refusedConnectionAroundNobodyWaits.initCause(refusal(404, "POST", "/v1/messages"));
        final var notANumberAroundNoAnswer = new NumberFormatException("For input string: \"order-1\"");
        notANumberAroundNoAnswer.initCause(noAnswer(new ConnectException("Connection refused")));
        // the client's exception above one this class would read without it
        final var notANumber = new NumberFormatException("For input string: \"x\"");
        final var unreadable = "was answered with status 200, but the answer cannot be read";
        final var notANumberBeneathAnUnreadableAnswer = new ZenBpmUnavailableException(ADAPTER_ID, 200, unreadable, notANumber, "GET", "/v1/jobs/42");
        // without an exception of the client, the first failure this class reads answers
        final var timeoutAroundNotANumber = new SocketTimeoutException("Read timed out");
        timeoutAroundNotANumber.initCause(new IllegalStateException("parsing failed", new NumberFormatException(
                "For input string: \"y\"")));

        assertAll(
                () -> assertEquals(EnumSet.of(ReadAs.PERMANENT), answersTo(timeoutAroundARefusal)),
                () -> assertEquals(EnumSet.of(ReadAs.PERMANENT), answersTo(new CompletionException(
                        timeoutAroundARefusal))),
                () -> assertEquals(EnumSet.of(ReadAs.NOBODY_WAITS, ReadAs.REPEATABLE), answersTo(
                        refusedConnectionAroundNobodyWaits)),
                () -> assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(
                        notANumberAroundNoAnswer)),
                () -> assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(new RuntimeException(
                        notANumberAroundNoAnswer))),
                () -> assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(
                        notANumberBeneathAnUnreadableAnswer)),
                () -> assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(
                        timeoutAroundNotANumber)));
    }

    @Test
    @DisplayName("A path which is not one of the job's own calls is no gone job and no lost lock")
    void onlyTheCallsOfAJobAreReadAsAJob() {
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(404, "GET", "/v1/jobs")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(404, "POST", "/v1/jobs/42/complete/again")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(404, "POST", "/system/v1/jobs/42/complete")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(409, "POST", "/v1/jobs/order-1/complete")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(409, "GET", "/v1/jobs/42/complete")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(409, "GET", "/v1/jobs/42/extend-lock")));
        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(refusal(404, "POST", null)));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("A chain of causes which closes into a ring is walked once, and an answer inside it still answers")
    void aRingOfCausesEndsTheWalk() {
        final var outer = new IllegalStateException("the outer failure");
        final var inner = new IllegalStateException("the inner failure", outer);
        outer.initCause(inner);
        final var wrapper = new IllegalStateException("the failure wrapped before");
        final var refusal = new ZenBpmApiException(ADAPTER_ID, 400, null, null, "POST", "/v1/process-instances");
        refusal.initCause(wrapper);
        wrapper.initCause(refusal);
        final var timeout = new SocketTimeoutException("Read timed out");
        final var refusalBeneathTheTimeout = new ZenBpmApiException(ADAPTER_ID, 400, null, null, "POST", "/v1/process-instances");
        timeout.initCause(refusalBeneathTheTimeout);
        refusalBeneathTheTimeout.initCause(timeout);
        final var timeoutWithoutTheClient = new SocketTimeoutException("Read timed out");
        final var wrappedTimeout = new IllegalStateException("the timeout wrapped", timeoutWithoutTheClient);
        timeoutWithoutTheClient.initCause(wrappedTimeout);

        assertEquals(EnumSet.of(ReadAs.REPEATABLE), answersTo(outer));
        assertEquals(EnumSet.of(ReadAs.PERMANENT), answersTo(wrapper));
        assertEquals(EnumSet.of(ReadAs.PERMANENT), answersTo(timeout));
        assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(wrappedTimeout));
    }

    @Test
    @DisplayName("The failures the client throws for the calls of a job, a message and a lock are read by their paths")
    void theClientsOwnFailuresAreRead() {
        try (var engine = FakeEngine.started(); var client = clientOf(engine.address())) {
            engine.answers("POST", "/v1/jobs/42/complete", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"job 42\"}");
            engine.answers("POST", "/v1/jobs/43/complete", 409, "{\"code\":\"CONFLICT\",\"message\":\"failed\"}");
            engine.answers("POST", "/v1/jobs/43/fail", 409, "{\"code\":\"CONFLICT\",\"message\":\"failed\"}");
            engine.answers("POST", "/v1/jobs/43/extend-lock", 409, "{\"code\":\"CONFLICT\",\"message\":\"lapsed\"}");
            engine.answers("POST", "/v1/messages", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"nobody\"}");
            engine.answers("POST", "/v1/process-instances", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"ttl\"}");
            engine.answers("GET", "/v1/process-instances/1", 502, "{\"code\":\"CLUSTER_ERROR\",\"message\":\"p0\"}");

            final var unknownJob = assertThrows(ZenBpmApiException.class, () -> client.completeJob(42, null));
            final var completionOfAFailedJob = assertThrows(ZenBpmApiException.class,
                    () -> client.completeJob(43, null));
            final var failureOfAFailedJob = assertThrows(ZenBpmApiException.class,
                    () -> client.failJob(43, null, null));
            final var lapsedLock = assertThrows(ZenBpmApiException.class,
                    () -> client.extendJobLock(43, "orders-node-1",
                            Duration.ofMinutes(5)));
            final var message = assertThrows(ZenBpmApiException.class, () -> client.publishMessage("paid", "order-1",
                    null));
            final var start = assertThrows(ZenBpmApiException.class, () -> client.createInstance(CreateInstanceRequest
                    .latestVersionOf("orders_order")));
            final var read = assertThrows(ZenBpmApiException.class, () -> client.instance(1));

            assertAll(
                    () -> assertTrue(ZenBpmErrors.isGoneJob(unknownJob)),
                    () -> assertTrue(ZenBpmErrors.isGoneJob(completionOfAFailedJob)),
                    () -> assertTrue(ZenBpmErrors.isGoneJob(failureOfAFailedJob)),
                    () -> assertTrue(ZenBpmErrors.lockLost(lapsedLock)),
                    () -> assertTrue(ZenBpmErrors.nobodyWaits(message)),
                    () -> assertTrue(ZenBpmErrors.permanentFailure(start)),
                    () -> assertTrue(ZenBpmErrors.isUnavailable(read)));
        }
    }

    @Test
    @DisplayName("An engine nobody answers for is unavailable")
    void anEngineNobodyAnswersForIsUnavailable() throws IOException {
        final int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (var unreachable = clientOf("http://127.0.0.1:%d".formatted(closedPort))) {
            final var failure = assertThrows(ZenBpmUnavailableException.class, unreachable::status);

            assertEquals(EnumSet.of(ReadAs.UNAVAILABLE, ReadAs.REPEATABLE), answersTo(failure));
        }
    }

    @Test
    @DisplayName("A message is given without the adapter id it starts with, and only that adapter id is left out")
    void aMessageWithoutTheAdapterId() {
        assertAll(
                () -> assertEquals("GET /system/health/ready was answered with status 500", ZenBpmErrors
                        .withoutAdapterId(ADAPTER_ID, refusal(500, "GET", "/system/health/ready"))),
                () -> assertEquals("ZenBPM adapter 'billing': boom", ZenBpmErrors.withoutAdapterId(ADAPTER_ID,
                        new IllegalStateException("ZenBPM adapter 'billing': boom"))),
                () -> assertEquals("java.lang.NullPointerException", ZenBpmErrors.withoutAdapterId(ADAPTER_ID,
                        new NullPointerException())));
    }

    /**
     * Every row of the README's table, with the failures which stand for it. The first argument
     * is the row's first cell as the README writes it.
     */
    static Stream<Arguments> everyRowOfTheReadmeTable() {
        final var permanentStatuses = "status `400`, `413` or `415`";
        final var goneByStatus404 = "status `404` from a call of a job";
        final var goneByStatus409 = "status `409` from a completion or failure of a job";
        final var unavailableStatuses = "status `502`, `503` or `504`";
        final var unavailable = "`ZenBpmUnavailableException`";
        final var transport = "`SocketTimeoutException`, `HttpTimeoutException` or `ConnectException`";
        final var otherStatuses = "any other status, `500` included";
        return Stream.of(
                Arguments.of(permanentStatuses, ReadAs.PERMANENT, refusal(400, "POST", "/v1/process-instances")),
                Arguments.of(permanentStatuses, ReadAs.PERMANENT, refusal(413, "POST", "/v1/process-definitions")),
                Arguments.of(permanentStatuses, ReadAs.PERMANENT, refusal(415, "POST", "/v1/process-definitions")),
                // a request refused is refused, whatever it named
                Arguments.of(permanentStatuses, ReadAs.PERMANENT, refusal(400, "POST", "/v1/jobs/42/extend-lock")),
                Arguments.of("`NumberFormatException`", ReadAs.PERMANENT, new NumberFormatException(
                        "For input string: \"order-1\"")),
                Arguments.of(goneByStatus404, ReadAs.GONE_JOB, refusal(404, "POST", "/v1/jobs/42/complete")),
                Arguments.of(goneByStatus404, ReadAs.GONE_JOB, refusal(404, "POST", "/v1/jobs/42/fail")),
                Arguments.of(goneByStatus404, ReadAs.GONE_JOB, refusal(404, "POST", "/v1/jobs/42/extend-lock")),
                Arguments.of(goneByStatus404, ReadAs.GONE_JOB, refusal(404, "GET", "/v1/jobs/42")),
                Arguments.of(goneByStatus409, ReadAs.GONE_JOB, refusal(409, "POST", "/v1/jobs/42/complete")),
                Arguments.of(goneByStatus409, ReadAs.GONE_JOB, refusal(409, "POST", "/v1/jobs/42/fail")),
                Arguments.of("status `404` from `POST /v1/messages`", ReadAs.NOBODY_WAITS, refusal(404, "POST",
                        "/v1/messages")),
                Arguments.of("status `409` from `POST /v1/jobs/{key}/extend-lock`", ReadAs.LOCK_LOST, refusal(409,
                        "POST", "/v1/jobs/42/extend-lock")),
                Arguments.of(unavailableStatuses, ReadAs.UNAVAILABLE, refusal(502, "GET", "/v1/process-instances/1")),
                // unavailable, not gone, although a job was called
                Arguments.of(unavailableStatuses, ReadAs.UNAVAILABLE, refusal(503, "POST", "/v1/jobs/42/complete")),
                Arguments.of(unavailableStatuses, ReadAs.UNAVAILABLE, refusal(504, "POST", "/v1/messages")),
                Arguments.of(unavailable, ReadAs.UNAVAILABLE, noAnswer(new HttpConnectTimeoutException("timed out"))),
                // the engine started the instance, and only its answer is lost
                Arguments.of(unavailable, ReadAs.UNAVAILABLE, unreadable(201, "POST", "/v1/process-instances")),
                // an expected 404 whose body stalled is not the engine's word on the job
                Arguments.of(unavailable, ReadAs.UNAVAILABLE, bodyLost(404, "GET", "/v1/jobs/42")),
                Arguments.of(transport, ReadAs.UNAVAILABLE, new SocketTimeoutException("Read timed out")),
                Arguments.of(transport, ReadAs.UNAVAILABLE, new HttpTimeoutException("request timed out")),
                Arguments.of(transport, ReadAs.UNAVAILABLE, new HttpConnectTimeoutException("connect timed out")),
                Arguments.of(transport, ReadAs.UNAVAILABLE, new ConnectException("Connection refused")),
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(500, "POST", "/v1/jobs/42/complete")),
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(401, "GET", "/v1/process-instances")),
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(429, "POST", "/v1/messages")),
                // a 404 of an instance, a 409 of a variable update, a 404 of a read of messages
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(404, "PATCH",
                        "/v1/process-instances/7/variables")),
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(409, "PATCH",
                        "/v1/process-instances/7/variables")),
                Arguments.of(otherStatuses, ReadAs.REPEATABLE, refusal(404, "GET", "/v1/messages")));
    }

    private static ZenBpmApiException refusal(
        final int status,
        final String method,
        final String path
    ) {
        return new ZenBpmApiException(ADAPTER_ID, status, null, null, method, path);
    }

    private static ZenBpmUnavailableException noAnswer(final Throwable cause) {
        return new ZenBpmUnavailableException(ADAPTER_ID, "got no answer", cause, "GET", "/v1/jobs/42");
    }

    // what the client throws where the status arrived, but the body stalled
    private static ZenBpmUnavailableException bodyLost(
        final int status,
        final String method,
        final String path
    ) {
        final var what = "was answered with status %d, but the body did not arrive".formatted(status);
        return new ZenBpmUnavailableException(ADAPTER_ID, status, what, new HttpTimeoutException("timed out"), method, path);
    }

    // what the client throws where the whole answer arrived, but cannot be read
    private static ZenBpmUnavailableException unreadable(
        final int status,
        final String method,
        final String path
    ) {
        final var what = "was answered with status %d, but the answer cannot be read".formatted(status);
        return new ZenBpmUnavailableException(ADAPTER_ID, status, what, new IOException("unexpected value"), method, path);
    }

    private static Set<ReadAs> answersTo(final Throwable failure) {
        final var answers = EnumSet.noneOf(ReadAs.class);
        if (ZenBpmErrors.permanentFailure(failure)) {
            answers.add(ReadAs.PERMANENT);
        }
        if (ZenBpmErrors.isGoneJob(failure)) {
            answers.add(ReadAs.GONE_JOB);
        }
        if (ZenBpmErrors.nobodyWaits(failure)) {
            answers.add(ReadAs.NOBODY_WAITS);
        }
        if (ZenBpmErrors.lockLost(failure)) {
            answers.add(ReadAs.LOCK_LOST);
        }
        if (ZenBpmErrors.isUnavailable(failure)) {
            answers.add(ReadAs.UNAVAILABLE);
        }
        if (ZenBpmErrors.isRepeatable(failure)) {
            answers.add(ReadAs.REPEATABLE);
        }
        return answers;
    }

    /**
     * The first two cells of every row of the README's table, in the README's order. A row named
     * twice fails the test, because the second could contradict the first.
     */
    private static List<Map.Entry<String, String>> rowsOfTheReadmeTable() throws IOException, URISyntaxException {
        final var readme = Files.readString(repositoryRoot().resolve("README.md"));
        final var start = readme.indexOf(README_SECTION);
        assertTrue(start >= 0, "README.md has no section '%s' any more".formatted(README_SECTION));
        final var rows = new ArrayList<Map.Entry<String, String>>();
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
            final var row = cells[1].strip();
            assertTrue(named.add(row), "README.md names the row '%s' twice".formatted(row));
            rows.add(Map.entry(row, cells[2].strip()));
        }
        assertFalse(rows.isEmpty(), "The section '%s' of README.md has no table".formatted(README_SECTION));
        return rows;
    }

    // the test classes are in <module>/target/test-classes, and the module in the repository root
    private static Path repositoryRoot() throws URISyntaxException {
        return Path
                .of(ZenBpmErrorsTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .getParent()
                .getParent()
                .getParent();
    }

    private static ZenBpmRestClient clientOf(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setRequestTimeout(Duration.ofSeconds(1));
        return new ZenBpmRestClient(ADAPTER_ID, configuration);
    }

    /**
     * What the README's column "Read as" says, and what each answer means for the questions of
     * {@link ZenBpmErrors}: a permanent failure is not repeatable, every other one is.
     */
    enum ReadAs {

        PERMANENT("permanent"),

        GONE_JOB("gone job"),

        NOBODY_WAITS("nobody waits"),

        LOCK_LOST("lock lost"),

        UNAVAILABLE("unavailable"),

        REPEATABLE("repeatable");

        private final String label;

        ReadAs(final String label) {
            this.label = label;
        }

        Set<ReadAs> answers() {
            return this == PERMANENT
                    ? EnumSet.of(PERMANENT)
                    : EnumSet.of(this, REPEATABLE);
        }

        @Override
        public String toString() {
            return label;
        }

    }

}
