/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * What the job stream of the ZenBPM adapter needs in a native image, registered here so that no
 * application has to find it out from a stack trace.
 * <p>
 * The classes generated from the engine's proto are read by reflection: protobuf finds the
 * accessors of a message's fields by name wherever it treats the message generically, as it does
 * when it writes one as text. A native image allows reflection only on the classes it was told
 * about. Only a native build reads these registrations; a build in JVM mode ignores them.
 */
class ZenBpmNativeImageProcessor {

    /**
     * The package of the classes generated from the engine's proto, which holds nothing else.
     */
    static final String GENERATED_PACKAGE = "org.pbinitiative.zenbpmadapter.client.grpc";

    /**
     * The artifact which holds the generated classes. A build step sees its classes only once they
     * are in the Jandex index, which {@link IndexDependencyBuildItem} asks for.
     */
    private static final String CORE_GROUP_ID = "org.pbinitiative.zenbpm";

    private static final String CORE_ARTIFACT_ID = "zenbpm-vanillabp-adapter";

    /**
     * Quarkus builds the processor while it builds the application, to run the steps below.
     */
    ZenBpmNativeImageProcessor() {
    }

    /**
     * Indexes the adapter's core, so the step below finds the generated classes.
     *
     * @return The index request for the core
     */
    @BuildStep
    IndexDependencyBuildItem theCoreIsIndexed() {
        return new IndexDependencyBuildItem(CORE_GROUP_ID, CORE_ARTIFACT_ID);
    }

    /**
     * Registers every class generated from the engine's proto for reflection: the messages, their
     * builders and the enums, taken from the index rather than written down, because which classes
     * <code>protoc</code> writes follows the proto.
     *
     * @param index The combined Jandex index, which holds the core because of the step above
     * @param reflectiveClasses Where the registrations go
     */
    @BuildStep
    void theGeneratedJobStreamClassesAreReadByReflection(
        final CombinedIndexBuildItem index,
        final BuildProducer<ReflectiveClassBuildItem> reflectiveClasses
    ) {
        final var generatedClasses = index
                .getIndex()
                .getKnownClasses()
                .stream()
                .map(ClassInfo::name)
                .filter(name -> GENERATED_PACKAGE.equals(name.packagePrefix()))
                .map(DotName::toString)
                .sorted()
                .toArray(String[]::new);
        reflectiveClasses.produce(ReflectiveClassBuildItem
                .builder(generatedClasses)
                .constructors()
                .methods()
                .fields()
                .reason("protobuf reads the generated classes of the ZenBPM job stream by reflection")
                .build());
    }

}
