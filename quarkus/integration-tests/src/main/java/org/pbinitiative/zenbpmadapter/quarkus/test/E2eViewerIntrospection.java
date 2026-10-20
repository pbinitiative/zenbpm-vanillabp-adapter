/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests of the viewer/history API see of the running application: they
 * start the workflows of the process <code>Dispatching</code>, read their definitions, the BPMN of
 * a definition and their history, also through the history context of a call activity, all through
 * these <code>introspect/viewer/...</code> endpoints, because the application runs in a JVM of its
 * own. What the platform answers as not found is a <code>404</code>.
 */
@Path("/introspect/viewer")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eViewerIntrospection {

    @Inject
    E2eDispatchWorkflow dispatches;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eViewerIntrospection() {
    }

    /**
     * Starts the workflow of a new dispatch, and commits before it answers.
     *
     * @param id The id of the dispatch
     * @return The id
     */
    @POST
    @Path("/dispatches/{id}")
    @Transactional
    public Map<String, Object> startDispatch(@PathParam("id") final String id) {
        dispatches.start(new E2eDispatch(id));
        return Map.of("id", id);
    }

    /**
     * The definitions the workflow of a dispatch runs on.
     *
     * @param id The id of the dispatch
     * @param context The history context of a call activity, none for the workflow
     * @return Each definition with its id, process, version and the call activities using it
     */
    @GET
    @Path("/dispatches/{id}/definitions")
    @Transactional
    public List<Map<String, Object>> definitions(
        @PathParam("id") final String id,
        @QueryParam("context") final String context
    ) {
        try {
            return dispatches
                    .processService()
                    .getProcessDefinitions(found(id), context)
                    .stream()
                    .map(E2eViewerIntrospection::described)
                    .toList();
        } catch (final WorkflowNotFoundException unknown) {
            throw new NotFoundException(unknown.getMessage());
        }
    }

    /**
     * The BPMN of a definition.
     *
     * @param definitionId The id of the definition, as the viewer reported it
     * @return The BPMN
     */
    @GET
    @Path("/definitions/{definitionId}")
    @Produces(MediaType.TEXT_PLAIN)
    public String bpmnXml(@PathParam("definitionId") final String definitionId) {
        try (var xml = dispatches
                .processService()
                .getBpmnXml(definitionId)) {
            return new String(xml.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final ProcessDefinitionNotFoundException unknown) {
            throw new NotFoundException(unknown.getMessage());
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * The history of the workflow of a dispatch.
     *
     * @param id The id of the dispatch
     * @param context The history context of a call activity, none for the workflow
     * @return The definition, the start, the end, which is empty while the workflow runs, and each
     *         element with its id, type, history context and error, each empty where it has none
     */
    @GET
    @Path("/dispatches/{id}/history")
    @Transactional
    public Map<String, Object> history(
        @PathParam("id") final String id,
        @QueryParam("context") final String context
    ) {
        try {
            final var history = dispatches
                    .processService()
                    .getWorkflowHistory(found(id), context);
            final var described = new LinkedHashMap<String, Object>();
            described.put("processDefinitionId", history.processDefinitionId());
            described.put("startTime", String.valueOf(history.startTime()));
            described.put("endTime", history.endTime() == null ? "" : history
                    .endTime()
                    .toString());
            described.put("elements", history
                    .elementsHistory()
                    .stream()
                    .map(E2eViewerIntrospection::described)
                    .toList());
            return described;
        } catch (final WorkflowNotFoundException unknown) {
            throw new NotFoundException(unknown.getMessage());
        }
    }

    private E2eDispatch found(final String id) {
        final var dispatch = entityManager.find(E2eDispatch.class, id);
        if (dispatch == null) {
            throw new NotFoundException("no dispatch '%s'".formatted(id));
        }
        return dispatch;
    }

    private static Map<String, Object> described(final ProcessDefinition definition) {
        final var described = new LinkedHashMap<String, Object>();
        described.put("id", definition.id());
        described.put("bpmnProcessId", definition.bpmnProcessId());
        described.put("version", definition.version());
        described.put("usedByElements", definition.usedByElements() == null ? List.of() : definition
                .usedByElements());
        return described;
    }

    private static Map<String, Object> described(final WorkflowElementHistory element) {
        final var described = new LinkedHashMap<String, Object>();
        described.put("elementId", element.elementId());
        described.put("elementType", element
                .elementType()
                .name());
        described.put("context", element.secondaryWorkflowHistoryContext() == null ? "" : element
                .secondaryWorkflowHistoryContext());
        described.put("error", element.error() == null ? "" : element.error());
        return described;
    }

}
