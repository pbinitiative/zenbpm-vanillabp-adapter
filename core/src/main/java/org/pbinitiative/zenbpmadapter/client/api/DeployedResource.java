/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

/**
 * A BPMN or DMN file the engine accepted.
 *
 * @param key The key of the definition the file is now; for an unchanged file the key it had
 * @param created <code>true</code> where the file became a new version (status 201),
 *            <code>false</code> where the latest version had the same content (status 200)
 */
public record DeployedResource(
    long key,
    boolean created
) {
}
