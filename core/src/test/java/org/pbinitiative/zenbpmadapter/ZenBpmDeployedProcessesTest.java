/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.DeployedProcess;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the deployments of a start remember per adapter id: per workflow module and process the
 * definition the engine ended up with, found by process and by definition key.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeployedProcessesTest {

    @Test
    @DisplayName("A deployed process is found by its workflow module and plain id, its definition by key, its scoped id by module; a later deployment of the same process replaces it")
    void aDeployedProcessIsFoundByModuleProcessAndKey() {
        final var deployed = new ZenBpmDeployedProcesses();
        deployed.record(new DeployedProcess("shop", "Order", "shop__Order", 11, 1, null, "<order-1/>"));
        deployed.record(new DeployedProcess("shop", "Invoice", "shop__Invoice", 12, 4, "v4", "<invoice/>"));
        deployed.record(new DeployedProcess("billing", "Order", "billing__Order", 13, 2, null, "<billing/>"));
        deployed.record(new DeployedProcess("shop", "Order", "shop__Order", 14, 2, null, "<order-2/>"));

        assertAll(
                () -> assertEquals(Optional.of(14L), deployed.keyOf("shop", "Order")),
                () -> assertEquals(Optional.of(2), deployed.versionOf("shop", "Order")),
                () -> assertEquals(Optional.of(4), deployed.versionOf("shop", "Invoice")),
                () -> assertEquals(Optional.empty(), deployed.keyOf("shop", "Unknown")),
                () -> assertEquals(Optional.empty(), deployed.versionOf("hr", "Order")),
                () -> assertEquals(Optional.of("<order-2/>"), deployed.xmlOf(14)),
                () -> assertEquals(Optional.of("<order-1/>"), deployed.xmlOf(11)),
                () -> assertEquals(Optional.empty(), deployed.xmlOf(99)),
                () -> assertEquals(List.of("shop__Invoice", "shop__Order"), deployed.scopedIdsOf("shop")),
                () -> assertEquals(List.of(), deployed.scopedIdsOf("hr")),
                () -> assertTrue(deployed.ownsScopedProcessId("billing", "billing__Order")),
                () -> assertFalse(deployed.ownsScopedProcessId("shop", "billing__Order")));
    }

    @Test
    @DisplayName("The workflow module which serves a process is found by the id the engine knows the process under, whether the module deployed it or not")
    void theModuleServingAProcessIsFoundByItsScopedId() {
        final var deployed = new ZenBpmDeployedProcesses();
        deployed.recordServed("shop", "shop__Order");
        deployed.recordServed("shop", "shop__OrderBeforeTheRename");
        deployed.recordServed("billing", "billing__Order");

        assertAll(
                () -> assertEquals(Optional.of("shop"), deployed.moduleServing("shop__Order")),
                () -> assertEquals(Optional.of("shop"), deployed.moduleServing("shop__OrderBeforeTheRename")),
                () -> assertEquals(Optional.of("billing"), deployed.moduleServing("billing__Order")),
                () -> assertEquals(Optional.empty(), deployed.moduleServing("crm__Order")),
                () -> assertEquals(List.of(), deployed.scopedIdsOf("shop")));
    }

    @Test
    @DisplayName("A process a workflow module serves stays that module's when another module records it, which learns who serves it; the same module may record it again")
    void aProcessServedByOneModuleStaysThatModules() {
        final var deployed = new ZenBpmDeployedProcesses();

        final var first = deployed.recordServed("shop", "Order");
        final var again = deployed.recordServed("shop", "Order");
        final var other = deployed.recordServed("billing", "Order");

        assertAll(
                () -> assertEquals(Optional.empty(), first),
                () -> assertEquals(Optional.empty(), again),
                () -> assertEquals(Optional.of("shop"), other),
                () -> assertEquals(Optional.of("shop"), deployed.moduleServing("Order")));
    }


    @Test
    @DisplayName("A workflow module serves a process it deployed no model for only where it serves an id it deployed nothing under; another module's ids do not count; the messages of a module are remembered per module and replaced by those read again")
    void aModuleServesAProcessWithoutAModelOnlyForAnIdItDidNotDeploy() {
        final var deployed = new ZenBpmDeployedProcesses();
        deployed.record(new DeployedProcess("shop", "Order", "shop__Order", 11, 1, null, "<order/>"));
        deployed.recordServed("shop", "shop__Order");
        deployed.recordServed("billing", "billing__Invoice");
        final var servedWithModelsOnly = deployed.servesAProcessItDeployedNoModelFor("shop");
        deployed.recordServed("shop", "shop__OrderBeforeTheRename");
        final var messages = new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid"), Set.of(), Set.of(), List
                .of());
        final var readAgain = new ModuleMessages(List.of("shop__Order"), Set.of("shop__paid", "shop__settled"), Set
                .of(), Set.of(), List.of());
        deployed.recordMessages("shop", messages, () -> readAgain);
        final var keptBeforeReadingAgain = deployed.messagesOf("shop");

        assertAll(
                () -> assertFalse(servedWithModelsOnly),
                () -> assertTrue(deployed.servesAProcessItDeployedNoModelFor("shop")),
                () -> assertTrue(deployed.servesAProcessItDeployedNoModelFor("billing")),
                () -> assertFalse(deployed.servesAProcessItDeployedNoModelFor("crm")),
                () -> assertEquals(Optional.of(messages), keptBeforeReadingAgain),
                () -> assertEquals(Optional.of(readAgain), deployed.messagesReadAgain("shop")),
                () -> assertEquals(Optional.of(readAgain), deployed.messagesOf("shop")),
                () -> assertEquals(Optional.empty(), deployed.messagesReadAgain("billing")),
                () -> assertEquals(Optional.empty(), deployed.messagesOf("billing")));
    }

}
