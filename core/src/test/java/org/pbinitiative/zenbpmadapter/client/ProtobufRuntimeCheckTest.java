/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.google.protobuf.RuntimeVersion;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The check of the application's protobuf runtime against the <code>protoc</code> which generated
 * the job stream's classes.
 */
@ExtendWith(SuppressOutputExtension.class)
class ProtobufRuntimeCheckTest {

    /**
     * The runtime of this build, which is the version of the <code>protoc</code> of this build.
     */
    private static final String BUILD_VERSION = "%d.%d.%d%s".formatted(RuntimeVersion.MAJOR, RuntimeVersion.MINOR,
            RuntimeVersion.PATCH, RuntimeVersion.SUFFIX);

    @Test
    @DisplayName("The runtime of this build passes, and is the one the adapter's jar names")
    void theBuildsOwnRuntimePasses() {
        assertAll(
                () -> assertEquals(Optional.empty(), ProtobufRuntimeCheck.defect()),
                () -> assertDoesNotThrow(() -> ProtobufRuntimeCheck.requireCompatibleRuntime()),
                () -> assertEquals(Optional.empty(), ProtobufRuntimeCheck.defect(BUILD_VERSION)));
    }

    @Test
    @DisplayName("A runtime older than the protoc is refused, naming the dependency and the version it needs")
    void anOlderRuntimeIsRefused() {
        final var newerProtoc = "%d.%d.0".formatted(RuntimeVersion.MAJOR, RuntimeVersion.MINOR + 1);

        final var refusal = assertThrows(IllegalStateException.class, () -> ProtobufRuntimeCheck
                .requireCompatibleRuntime(newerProtoc));

        assertEquals(
                ("Detected incompatible Protobuf Gencode/Runtime versions when loading the job stream of the ZenBPM "
                        + "adapter: gencode %s, runtime %s. Runtime version cannot be older than the linked gencode version. "
                        + "The job stream of the ZenBPM adapter was generated for protobuf %s and needs "
                        + "'com.google.protobuf:protobuf-java' in version %s or a later version of the same major version. "
                        + "Raise 'com.google.protobuf:protobuf-java' to %s or later in the dependency management of the "
                        + "application; a framework's BOM may pin an older one.").formatted(newerProtoc, BUILD_VERSION,
                                newerProtoc, newerProtoc, newerProtoc),
                refusal.getMessage());
    }

    @Test
    @DisplayName("A runtime two major versions ahead is refused as well, by protobuf's own rule")
    void aRuntimeOfAnotherMajorVersionIsRefused() {
        final var olderProtoc = "%d.0.0".formatted(RuntimeVersion.MAJOR - 2);

        final var defect = ProtobufRuntimeCheck.defect(olderProtoc);

        assertTrue(defect
                .orElseThrow()
                .startsWith("Detected mismatched Protobuf Gencode/Runtime major versions"), defect.toString());
    }

    @Test
    @DisplayName("A runtime without RuntimeVersion, older than 4.26.0, is refused before a generated class fails")
    void aRuntimeWithoutRuntimeVersionIsRefused() throws ReflectiveOperationException {
        final var withoutRuntimeVersion = new WithoutRuntimeVersion(getClass().getClassLoader());
        final var isolated = withoutRuntimeVersion.loadClass(ProtobufRuntimeCheck.class.getName());
        final var defectOf = isolated.getDeclaredMethod("defect", String.class);
        defectOf.setAccessible(true);

        final var defect = (Optional<?>) defectOf.invoke(null, BUILD_VERSION);

        assertTrue(defect
                .orElseThrow()
                .toString()
                .startsWith("The protobuf runtime of the application has no 'com.google.protobuf.RuntimeVersion' "
                        + "(java.lang.NoClassDefFoundError: com/google/protobuf/RuntimeVersion"),
                defect.toString());
    }

    @Test
    @DisplayName("A version which is no protobuf version says the adapter's jar is broken")
    void aMalformedVersionIsABrokenJar() {
        final var broken = assertThrows(IllegalStateException.class, () -> ProtobufRuntimeCheck.defect(
                "${protobuf.version}"));

        assertEquals("'protobuf.gencode' of 'META-INF/vanillabp/adapter-zenbpm.properties' is '${protobuf.version}' "
                + "instead of a protobuf version, so the adapter's jar is broken. The resource is filtered from "
                + "'protobuf.version' of the adapter's root POM.", broken.getMessage());
    }

    /**
     * A class loader which loads {@link ProtobufRuntimeCheck} itself and knows no
     * <code>com.google.protobuf.RuntimeVersion</code>, like an application whose protobuf
     * runtime is older than 4.26.0.
     */
    private static final class WithoutRuntimeVersion extends ClassLoader {

        private WithoutRuntimeVersion(final ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(
            final String name,
            final boolean resolve
        ) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith(RuntimeVersion.class.getName())) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(ProtobufRuntimeCheck.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                final var loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                try (var bytes = getParent().getResourceAsStream(name.replace('.', '/')
                        + ".class")) {
                    final var code = bytes.readAllBytes();
                    // with the code source of the original, which the coverage agent needs to
                    // count what runs in this copy
                    return defineClass(name, code, 0, code.length, ProtobufRuntimeCheck.class.getProtectionDomain());
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }

    }

}
