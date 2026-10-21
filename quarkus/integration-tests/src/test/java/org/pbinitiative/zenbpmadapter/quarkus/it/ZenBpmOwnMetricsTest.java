/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A Quarkus application which uses the Micrometer extension and brings its own measurement of the
 * adapter's numbers next to the adapter's meter binder: the deployment services measure into the
 * application's one. Two beans of the type are ambiguous, so without the preference the deployment
 * services would measure nothing. The adapter id has no engine address,
 * so the application boots without an engine.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
class ZenBpmOwnMetricsTest {

    private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

    @RegisterExtension
    static final QuarkusProdModeTest application = new QuarkusProdModeTest()
            .withApplicationRoot(jar -> jar
                    .addPackage("org.pbinitiative.zenbpmadapter.quarkus.test.ownmetrics")
                    .addAsResource("ownmetrics/application.yaml", "application.yaml")
                    .addAsResource("workflow-module-descriptor/own-metrics-workflow-module",
                            "META-INF/workflow-module"))
            // the surefire configuration of this module hands 'jacoco.agent' to this JVM
            .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
            .setRun(true)
            .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(HTTP_PORT)));

    @Test
    @DisplayName("An application which brings its own ZenBpmMetrics boots, and the deployment service measures into it rather than into the adapter's meter binder")
    void anApplicationsOwnMetricsReplaceTheBinder() {
        final var measuredInto = RestAssured
                .given()
                .port(HTTP_PORT)
                .get("own-metrics")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList(".", String.class);

        assertEquals(List.of("OwnMetrics"), measuredInto);
    }

}
