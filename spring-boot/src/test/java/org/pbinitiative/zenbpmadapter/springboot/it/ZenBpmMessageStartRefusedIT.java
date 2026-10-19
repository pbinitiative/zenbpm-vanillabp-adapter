/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.springboot.it.message.MessageItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.message.Postcard;
import org.pbinitiative.zenbpmadapter.springboot.it.message.PostcardRepository;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * A booted Spring Boot application under <code>message-start-lookup: refuse</code>, against the
 * pinned engine: the start names the processes a message starts and the key which allows their
 * start, and a start by a message is refused where the application calls, naming the key, the gap
 * and the other two values, so its transaction rolls back and nothing is planned.
 * <p>
 * The application is the one of {@link ZenBpmMessageIT}, {@link MessageItApplication}, with the key
 * set to <code>refuse</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MessageItApplication.class, properties = {
        "spring.config.name=message-it", "vanillabp.adapters.zenbpm.message-start-lookup=refuse"
})
@DirtiesContext
class ZenBpmMessageStartRefusedIT {

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Postcard> processService;

    @Autowired
    private PostcardRepository postcards;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Under message-start-lookup refuse, a start by a message is refused where the application calls, naming the key, the gap and the other two values; its transaction rolls back, nothing is planned, and the start named the key")
    void startWorkflowByMessageIsRefusedWithRefuse(final CapturedOutput output) {
        final var postcardId = "postcard-%s".formatted(UUID.randomUUID());

        final var refusal = assertThrows(IllegalStateException.class, () -> transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflowByMessage(new Postcard(postcardId),
                        "postcardPosted")));

        assertAll(
                () -> assertEquals("""
                        ZenBPM adapter 'zenbpm' does not start workflow 'Postcards' of workflow module 'deploy-it' by \
                        message 'postcardPosted': vanillabp.adapters.zenbpm.message-start-lookup is 'refuse'. ZenBPM \
                        gives a workflow it starts by a message no business key, and none can be set afterwards (a \
                        gap of the engine which GAPS.md of zenbpm-vanillabp-adapter lists), so the adapter finds such \
                        a workflow only through the instance key its start marker writes into the start row of the \
                        aggregate. Set the key to 'start-row', the default, to start it and find it that way, or to \
                        'scan' to find a running one also where the start row does not know it yet.""", refusal
                        .getMessage()),
                () -> assertTrue(postcards
                        .findById(postcardId)
                        .isEmpty()),
                () -> assertEquals(List.of(), PhaseTwoOutboxReader
                        .of(dataSource)
                        .entries()
                        .stream()
                        .filter(entry -> postcardId.equals(entry.aggregateId()))
                        .toList()),
                () -> assertTrue(output
                        .getAll()
                        .contains(
                                "ZenBPM[zenbpm]: workflow module 'deploy-it' has processes a message starts: 'Postcards'. vanillabp.adapters.zenbpm.message-start-lookup is 'refuse': startWorkflowByMessage is refused for them. Set the key to 'start-row', the default, or to 'scan' to start them."),
                        output::getAll));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

}
