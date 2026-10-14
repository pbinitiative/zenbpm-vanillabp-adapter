/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.io.IOException;

/**
 * A class loader which knows no <code>com.google.protobuf.RuntimeVersion</code>, like an
 * application whose protobuf runtime is older than 4.26.0. It loads the adapter's client classes
 * itself, the generated job stream classes among them, so they meet the missing class as they
 * would there; everything else comes from the loader of the tests.
 */
final class WithoutRuntimeVersion extends ClassLoader {

    private static final String RUNTIME_VERSION = "com.google.protobuf.RuntimeVersion";

    private static final String CLIENT_PACKAGE = "org.pbinitiative.zenbpmadapter.client.";

    WithoutRuntimeVersion(final ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(
        final String name,
        final boolean resolve
    ) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (name.startsWith(RUNTIME_VERSION)) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith(CLIENT_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            final var loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try (var bytes = getParent().getResourceAsStream(name.replace('.', '/')
                    + ".class")) {
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                final var code = bytes.readAllBytes();
                // with the code source of the original, which the coverage agent needs to count
                // what runs in this copy
                return defineClass(name, code, 0, code.length, ProtobufRuntimeCheck.class.getProtectionDomain());
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

}
