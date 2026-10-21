/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.ProtobufRuntimeCheck;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.ZenBpmClientProducer;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.ZenBpmDeploymentServiceProducer;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.ZenBpmMetricsProducer;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.ZenBpmProcessServiceProducer;
import org.pbinitiative.zenbpmadapter.quarkus.runtime.ZenBpmStartupObserver;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.integration.deployment.pipeline.VanillaBpAdapterDeploymentServiceBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;

/**
 * The Quarkus extension of the ZenBPM adapter. It tells the VanillaBP Quarkus integration the
 * adapter type and the producers of its process services and deployment services, registers the
 * producer of the clients and the observer which builds them at startup, and refuses to build an
 * application whose protobuf runtime cannot run the job stream.
 * <p>
 * The adapter has no build-time configuration root, not even an empty one. An empty build-time
 * root under the prefix <code>vanillabp</code> turns off the check for unknown keys in the whole
 * <code>vanillabp.*</code> tree, so an application with a misspelt key would start.
 * <code>ZenBpmUnknownKeyTest</code> fails if that happens.
 */
class ZenBpmIntegrationProcessor {

    private static final String FEATURE = "vanillabp-zenbpm";

    private static final String MICROMETER_EXTENSION_PROCESSOR = "io.quarkus.micrometer.deployment.MicrometerProcessor";

    /**
     * Quarkus builds the processor while it builds the application, to run the steps below.
     */
    ZenBpmIntegrationProcessor() {
    }

    /**
     * Announces the producer of the process services. The VanillaBP Quarkus integration registers
     * the announced bean class, so it is the producer and not the process service of the core.
     *
     * @param features Where the extension's feature is registered
     * @return The announcement
     */
    @BuildStep
    VanillaBpMigratableProcessServiceBuildItem buildProcessServices(final BuildProducer<FeatureBuildItem> features) {
        features.produce(new FeatureBuildItem(FEATURE));
        return VanillaBpMigratableProcessServiceBuildItem
                .builder()
                .adapterType(ZenBpmAdapter.ADAPTER_TYPE)
                .migratableProcessServiceBeanClass(ZenBpmProcessServiceProducer.class.getName())
                .build();
    }

    /**
     * Announces the producer of the deployment services, which the platform's deployment pipeline
     * runs while the application starts. The VanillaBP Quarkus integration registers the announced
     * bean class.
     *
     * @return The announcement
     */
    @BuildStep
    VanillaBpAdapterDeploymentServiceBuildItem buildDeploymentServices() {
        return VanillaBpAdapterDeploymentServiceBuildItem
                .builder()
                .adapterType(ZenBpmAdapter.ADAPTER_TYPE)
                .deploymentServiceBeanClass(ZenBpmDeploymentServiceProducer.class.getName())
                .build();
    }

    /**
     * Registers the producer of the clients and the observer which builds them while the
     * application starts. Nothing injects the observer, and only the producers announced above ask
     * for the registry, so both are kept although CDI would see no use of them.
     *
     * @return The registration
     */
    @BuildStep
    AdditionalBeanBuildItem registerClientProducerAndStartupObserver() {
        return AdditionalBeanBuildItem
                .builder()
                .addBeanClass(ZenBpmClientProducer.class)
                .addBeanClass(ZenBpmStartupObserver.class)
                .setUnremovable()
                .build();
    }

    /**
     * Registers the producer of this adapter's own meters, but only for an application which uses
     * the Micrometer extension. The adapter does not bring Micrometer: metrics are the
     * application's decision, and an application without them boots unchanged.
     * <p>
     * The signal is the build-step class of the Micrometer extension on the deployment classpath,
     * as the VanillaBP platform integration detects it. Micrometer's own classes are no signal: a
     * library may bring them as a dependency of its own, and then nothing produces the
     * {@code MeterRegistry} the binder is applied to. A step which produces nothing is never run by
     * Quarkus, so it produces the registration or nothing through the producer it is handed.
     *
     * @param additionalBeans Where the producer of the meters is registered
     */
    @BuildStep
    void registerTheMetersWhereTheApplicationUsesMicrometer(final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (!isOnTheDeploymentClasspath(MICROMETER_EXTENSION_PROCESSOR)) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem
                .builder()
                .addBeanClass(ZenBpmMetricsProducer.class)
                // applied by Micrometer's own startup and asked for by the deployment services
                // through an Instance, which CDI does not count as a use
                .setUnremovable()
                .build());
    }

    /**
     * Refuses an application whose protobuf runtime cannot run the job stream, while it is built
     * and not when the first job stream class is loaded in production. The classes this step sees
     * are those the application is built with, protobuf's included, so the answer is the one the
     * application would meet at runtime.
     * <p>
     * The refusal goes to the validation of CDI, which ends the build with every error it collected
     * and names this one with its message. A step which produces nothing is never run by Quarkus.
     *
     * @param errors Where a refusal goes
     */
    @BuildStep
    void theProtobufRuntimeCanRunTheJobStream(final BuildProducer<ValidationErrorBuildItem> errors) {
        ProtobufRuntimeCheck
                .defect()
                .ifPresent(defect -> errors.produce(new ValidationErrorBuildItem(new IllegalStateException(
                        defect))));
    }

    private static boolean isOnTheDeploymentClasspath(final String className) {
        try {
            Class.forName(className, false, Thread
                    .currentThread()
                    .getContextClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

}
