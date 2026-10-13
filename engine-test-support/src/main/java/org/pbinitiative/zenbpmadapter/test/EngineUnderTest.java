/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The ZenBPM engine an integration test runs against: one node, started from the image this
 * repository pins.
 * <p>
 * The image is not written into a test. At build time it is filtered into
 * {@code zenbpm-engine.properties} from {@code zenbpm.image} of the root POM, together with
 * the engine commit it was built from. So moving the pin moves every test with it, and the
 * supported engine of the README is the one the tests met, see decision 15 in the
 * repository's DECISIONS.md.
 * <p>
 * A class declares it as a Testcontainers {@code @Container} field, which starts it before
 * the first test and stops it when the class is done. What the engine prints goes to
 * {@link EngineLog}.
 */
public final class EngineUnderTest extends GenericContainer<EngineUnderTest> {

  private static final String RESOURCE = "/zenbpm-engine.properties";

  private static final Properties PROPERTIES = read();

  private static final String IMAGE = property("engine.image");

  private static final String COMMIT = property("engine.commit");

  private static final int REST_PORT = 8080;

  private static final int GRPC_PORT = 9090;

  /**
   * How long the engine may take to answer ready. It took six seconds on a laptop; the limit
   * is where a run gives up, so an engine which starts normally does not pay it.
   */
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

  /**
   * An engine which is not started yet, ready to be declared as a {@code @Container} field.
   */
  public EngineUnderTest() {

    super(image());
    withExposedPorts(REST_PORT, GRPC_PORT);
    withEnv("REST_API_ADDR", ":%d".formatted(REST_PORT));
    withEnv("GRPC_API_ADDR", ":%d".formatted(GRPC_PORT));
    // one node which elects itself leader instead of waiting for others to join
    withEnv("CLUSTER_RAFT_BOOTSTRAP_EXPECT", "1");
    // the engine looks for due timers every ten seconds by default, which a test waiting for
    // a timer event would spend waiting
    withEnv("POLL_TIMER_DELAY_SECONDS", "1");
    // no time to live for finished instances, so a test can still read an instance after it
    // ended. 0 is today's default of the engine, written here so that a new default does not
    // change what a test finds
    withEnv("PERSISTENCE_INSTANCE_HISTORY_TTL", "0");
    withLogConsumer(EngineLog.consumer());
    // 503 until the node is the leader and its partitions are initialized, so a test's first
    // request does not race the election
    waitingFor(
        Wait
            .forHttp("/system/health/ready")
            .forPort(REST_PORT)
            .forStatusCode(200)
            .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * The image every engine of this build starts from.
   *
   * @return The pinned image, by digest
   */
  public static DockerImageName image() {

    return DockerImageName.parse(IMAGE);

  }

  /**
   * The engine commit the pinned image was built from. The engine reports it shortened in
   * {@code git.commitId} of {@code GET /system/status}.
   *
   * @return The full commit, 40 hexadecimal characters
   */
  public static String pinnedCommit() {

    return COMMIT;

  }

  /**
   * Where the started engine answers REST calls. The port is mapped, so it is known only once
   * the container runs.
   *
   * @return The base address without a path, e.g. {@code http://localhost:32770}
   */
  public String restAddress() {

    return "http://%s:%d".formatted(getHost(), getMappedPort(REST_PORT));

  }

  /**
   * Where the started engine answers gRPC calls, in the form a gRPC channel takes as its target.
   * The engine serves it without TLS.
   *
   * @return Host and port, e.g. {@code localhost:32771}
   */
  public String grpcAddress() {

    return "%s:%d".formatted(getHost(), getMappedPort(GRPC_PORT));

  }

  private static Properties read() {

    final var properties = new Properties();
    try (var resource = EngineUnderTest.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException(
            ("'%s' is missing from the classpath. It is filtered into the artifact "
                + "'zenbpm-vanillabp-adapter-engine-test-support', so build that module once "
                + "('mvn install') before running an integration test from the IDE.").formatted(RESOURCE));
      }
      properties.load(resource);
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read '%s'".formatted(RESOURCE), e);
    }
    return properties;

  }

  private static String property(
      final String name) {

    final var value = PROPERTIES.getProperty(name);
    if ((value == null) || value.isBlank() || value.contains("${")) {
      throw new IllegalStateException(
          ("'%s' of '%s' is '%s' instead of a value. The resources of 'engine-test-support' have "
              + "to be filtered: check its pom.xml and the properties 'zenbpm.image' and "
              + "'zenbpm.commit' of the root pom.").formatted(name, RESOURCE, value));
    }
    return value;

  }

}
