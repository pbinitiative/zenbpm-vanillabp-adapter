/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.DefinitionStatistics;
import org.pbinitiative.zenbpmadapter.client.api.DeployedResource;
import org.pbinitiative.zenbpmadapter.client.api.EngineStatus;
import org.pbinitiative.zenbpmadapter.client.api.HistoryEntry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.Page;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.client.api.Readiness;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The REST client of one adapter id: one method per endpoint of the engine the adapter uses,
 * each answering records of the <code>api</code> package, never the engine's JSON. It is written
 * against the copy of the engine's <code>api.yaml</code> this repository pins, see decision 3 in
 * the repository's DECISIONS.md.
 * <p>
 * Every call ends within <code>request-timeout</code>, counted from sending the request. A call
 * whose answer started but whose body stopped arriving is given a quarter of a second more. The
 * whole body has to arrive in that time, so a large answer over a slow link may run out of it.
 * <p>
 * A status the call does not expect throws {@link ZenBpmApiException}, also where its body never
 * arrives: the status is what the engine decided, the body only explains it. No answer the adapter
 * can use throws {@link ZenBpmUnavailableException}: the engine unreachable or too slow, the
 * connection broken, an answer which cannot be read. The engine adds fields between minor
 * versions, so a field the adapter does not know is ignored. A call after {@link #close()} throws
 * {@link IllegalStateException}.
 * <p>
 * The engine's business API lives under <code>/v1</code> of <code>rest-address</code>, its
 * operational endpoints under <code>/system</code> beside it. Building a client contacts nothing.
 * A client may be used by several threads at once.
 */
public class ZenBpmRestClient implements AutoCloseable {

    /**
     * How many items a page holds where the client reads a list to its end: the largest page
     * every endpoint read that way hands out.
     */
    static final int PAGE_SIZE = 100;

    private static final String GET = "GET";

    private static final String POST = "POST";

    private static final String PATCH = "PATCH";

    private static final String JSON = "application/json";

    private static final String OCTET_STREAM = "application/octet-stream";

    /**
     * How much longer than <code>request-timeout</code> the client waits for a body which stopped
     * arriving. The HTTP client's own timeouts end a call whose connection or headers do not come.
     * The margin lets them act first, so such a call always names the same cause.
     */
    private static final Duration BODY_TIMEOUT_MARGIN = Duration.ofMillis(250);

    private static final IntPredicate SUCCESS = status -> (status >= 200) && (status < 300);

    private static final IntPredicate SUCCESS_OR_NOT_FOUND = SUCCESS.or(status -> status == 404);

    /**
     * Reads the variables of a job the job stream handed over, which carries them as JSON. A value
     * of no declared type is decoded as the REST answers decode it.
     */
    private static final ObjectMapper JOB_VARIABLES = JsonMapper
            .builder()
            .build();

    private final String adapterId;

    private final Duration requestTimeout;

    private final String engineAddress;

    private final HttpClient http;

    private final ObjectMapper json;

    private volatile boolean closed;

    /**
     * A client of the engine a configuration names. Contacts nothing.
     *
     * @param adapterId The adapter id, named by every exception the client throws
     * @param configuration A configuration which {@link ZenBpmAdapterConfiguration#validate} found
     *            complete
     */
    public ZenBpmRestClient(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration
    ) {
        this.adapterId = adapterId;
        this.requestTimeout = configuration.getRequestTimeout();
        this.engineAddress = configuration
                .engineAddress()
                .toString();
        this.http = HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(requestTimeout)
                .build();
        this.json = JsonMapper
                .builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // a key with a fraction is no key, rather than a key cut down to a whole number
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .annotationIntrospector(new RequiredMeansNotNull())
                // a field left null is not sent, but a variable set to null is
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

    /**
     * Whether the engine is ready (<code>GET /system/health/ready</code>). Not ready is an answer,
     * not a failure.
     *
     * @return Ready for status 200, not ready with the engine's reasons for status 503
     */
    public Readiness ready() {
        final var call = new Call(GET, "/system/health/ready");
        final var answer = call.send(status -> (status == 200) || (status == 503));
        return call.decoded(answer, ZenBpmWire.Health.class,
                health -> new Readiness(answer.status() == 200, Objects.requireNonNullElse(health.reasons(),
                        List.of())));
    }

    /**
     * The engine's build and cluster (<code>GET /system/status</code>).
     *
     * @return Version, commit, node ids and partition ids
     */
    public EngineStatus status() {
        final var call = new Call(GET, "/system/status");
        return call.decoded(call.send(SUCCESS), ZenBpmWire.SystemStatus.class, status -> {
            final var nodes = status
                    .nodes()
                    .keySet()
                    .stream()
                    .sorted()
                    .toList();
            final var version = status
                    .build()
                    .version();
            final var commit = status
                    .git()
                    .commitId();
            final var partitions = Objects
                    .requireNonNullElse(status.partitions(), Map.<String, Object>of())
                    .keySet()
                    .stream()
                    .map(Integer::valueOf)
                    .sorted()
                    .toList();
            return new EngineStatus(version, commit, nodes, partitions);
        });
    }

    /**
     * Deploys a BPMN file (<code>POST /v1/process-definitions</code>). A file whose content equals
     * the latest version of its process is no new version.
     *
     * @param bpmn The file
     * @return The definition key, and whether the file became a new version
     */
    public DeployedResource deployBpmn(final byte[] bpmn) {
        final var call = new Call(POST, "/v1/process-definitions");
        final var answer = call.send(OCTET_STREAM, BodyPublishers.ofByteArray(bpmn), SUCCESS);
        return call.decoded(answer, ZenBpmWire.ProcessDefinitionCreated.class,
                created -> new DeployedResource(created.processDefinitionKey(), answer.status() == 201));
    }

    /**
     * Deploys a DMN file (<code>POST /v1/dmn-resource-definitions</code>). A file whose content
     * equals the latest version of its definitions is no new version.
     *
     * @param dmn The file
     * @return The DMN resource definition key, and whether the file became a new version
     */
    public DeployedResource deployDmn(final byte[] dmn) {
        final var call = new Call(POST, "/v1/dmn-resource-definitions");
        final var answer = call.send(OCTET_STREAM, BodyPublishers.ofByteArray(dmn), SUCCESS);
        return call.decoded(answer, ZenBpmWire.DmnResourceDefinitionCreated.class,
                created -> new DeployedResource(created.dmnResourceDefinitionKey(), answer.status() == 201));
    }

    /**
     * One version of a process, with its BPMN (<code>GET /v1/process-definitions/{key}</code>).
     *
     * @param key The process definition key
     * @return The version
     * @throws ZenBpmApiException With status 404 where the engine knows no such definition
     */
    public ProcessDefinitionRef processDefinition(final long key) {
        final var call = new Call(GET, "/v1/process-definitions/%d".formatted(key));
        return call.decoded(call.send(SUCCESS), ZenBpmWire.ProcessDefinition.class,
                ZenBpmRestClient::processDefinitionRef);
    }

    /**
     * The versions of a process (<code>GET /v1/process-definitions</code>), read page by page to
     * the end, ordered by key. They carry no BPMN.
     *
     * @param bpmnProcessId The id of the process
     * @param onlyLatest <code>true</code> for the latest version only
     * @return The versions, empty where the engine knows no such process
     */
    public List<ProcessDefinitionRef> processDefinitions(
        final String bpmnProcessId,
        final boolean onlyLatest
    ) {
        return everyPage(page -> processDefinitionsPage(bpmnProcessId, onlyLatest, page));
    }

    /**
     * How many element instances of one version are active or carry an incident, per BPMN element
     * (<code>GET /v1/process-definitions/{key}/statistics</code>), summed over the engine's
     * partitions.
     *
     * @param key The process definition key
     * @return The counts by element id
     * @throws ZenBpmApiException With status 404 where the engine knows no such definition
     */
    public DefinitionStatistics processDefinitionStatistics(final long key) {
        final var call = new Call(GET, "/v1/process-definitions/%d/statistics".formatted(key));
        return call.decoded(call.send(SUCCESS), ZenBpmWire.ElementStatisticsPartitions.class, statistics -> {
            final var elements = new HashMap<String, DefinitionStatistics.ElementCounts>();
            statistics
                    .partitions()
                    .forEach(partition -> partition
                            .items()
                            .forEach((
                                elementId,
                                counts) -> elements.merge(
                                        elementId,
                                        new DefinitionStatistics.ElementCounts(counts.activeCount(), counts
                                                .incidentCount()),
                                        (
                                            sum,
                                            more) -> new DefinitionStatistics.ElementCounts(sum.active() + more
                                                    .active(), sum.incidents() + more.incidents()))));
            return new DefinitionStatistics(elements);
        });
    }

    /**
     * Starts a process instance (<code>POST /v1/process-instances</code>).
     *
     * @param request What to start, and with what
     * @return The new instance as the engine reports it right after the start
     * @throws ZenBpmApiException With status 404 where the engine knows no such process
     */
    public ProcessInstanceRef createInstance(final CreateInstanceRequest request) {
        final var call = new Call(POST, "/v1/process-instances");
        final var body = call.jsonBody(new ZenBpmWire.CreateProcessInstance(
                request.bpmnProcessId(), request.processDefinitionKey(), request.businessKey(), request
                        .variables(), request.historyTimeToLive()));
        return call.decoded(call.send(JSON, body, SUCCESS), ZenBpmWire.ProcessInstance.class,
                ZenBpmRestClient::processInstanceRef);
    }

    /**
     * One process instance (<code>GET /v1/process-instances/{key}</code>).
     *
     * @param key The process instance key
     * @return The instance, empty where the engine answers 404
     * @throws ZenBpmApiException With status 502 where the key names a partition the engine does
     *             not have: a key carries the partition which holds it
     */
    public Optional<ProcessInstanceRef> instance(final long key) {
        final var call = new Call(GET, "/v1/process-instances/%d".formatted(key));
        final var answer = call.send(SUCCESS_OR_NOT_FOUND);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional
                .of(call.decoded(answer, ZenBpmWire.ProcessInstance.class, ZenBpmRestClient::processInstanceRef));
    }

    /**
     * Where the tokens of a process instance stand now (the <code>activeElementInstances</code> of
     * <code>GET /v1/process-instances/{key}</code>): one element id per token which runs, waits or
     * failed, an id twice where two tokens stand at one element. The tokens of its child instances
     * are not among them; the token which waits for a child stands at the element which started it.
     *
     * @param key The process instance key
     * @return The element ids, empty where the engine answers 404
     * @throws ZenBpmApiException With status 502 where the key names a partition the engine does
     *             not have: a key carries the partition which holds it
     */
    public Optional<List<String>> activeElementsOf(final long key) {
        final var call = new Call(GET, "/v1/process-instances/%d".formatted(key));
        final var answer = call.send(SUCCESS_OR_NOT_FOUND);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(call.decoded(answer, ZenBpmWire.ProcessInstanceTokens.class, read -> read
                .activeElementInstances()
                .stream()
                .map(ZenBpmWire.ElementInstance::elementId)
                .toList()));
    }

    /**
     * The keys of the tokens which stand at one element of a process instance now (the
     * <code>activeElementInstances</code> of <code>GET /v1/process-instances/{key}</code>). The
     * token which waits for the iterations of a multi-instance element stands at that element under
     * the key of the element's entry in the instance's history.
     *
     * @param key The process instance key
     * @param elementId The element
     * @return The keys, empty where the engine answers 404
     * @throws ZenBpmApiException With status 502 where the key names a partition the engine does
     *             not have: a key carries the partition which holds it
     */
    public Optional<List<Long>> tokensAt(
        final long key,
        final String elementId
    ) {
        final var call = new Call(GET, "/v1/process-instances/%d".formatted(key));
        final var answer = call.send(SUCCESS_OR_NOT_FOUND);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(call.decoded(answer, ZenBpmWire.ProcessInstanceActivations.class, read -> read
                .activeElementInstances()
                .stream()
                .filter(token -> elementId.equals(token.elementId()))
                .map(ZenBpmWire.Activation::elementInstanceKey)
                .toList()));
    }

    /**
     * One page of a search for process instances (<code>GET /v1/process-instances</code>),
     * ordered by key within each partition of the engine. The page size applies per partition;
     * {@link Page#totalCount()} counts every match.
     *
     * @param filter Which instances, and which page
     * @return The page
     */
    public Page<ProcessInstanceRef> instances(final InstanceFilter filter) {
        final var query = new LinkedHashMap<String, Object>();
        query.put("businessKey", filter.businessKey());
        query.put("bpmnProcessId", filter.bpmnProcessId());
        query.put("processDefinitionKey", filter.processDefinitionKey());
        query.put("state", wireName(filter.state()));
        query.put("createdFrom", filter.createdFrom());
        sortedByKey(query, filter.page(), filter.size());
        final var call = new Call(GET, "/v1/process-instances", query);
        return call.decoded(call.send(SUCCESS), ZenBpmWire.ProcessInstancePage.class,
                read -> partitionedPage(read.partitions(), read.totalCount(), filter.page(), filter.size(),
                        ZenBpmRestClient::processInstanceRef));
    }

    /**
     * The process instances one instance started: its sub-processes, call activities and
     * multi-instance iterations, in every state (<code>GET
     * /v1/process-instances/{key}/child-processes</code>), read page by page to the end.
     *
     * @param key The key of the parent instance
     * @return The children, ordered by key within each partition of the engine
     */
    public List<ProcessInstanceRef> children(final long key) {
        return everyPage(page -> childrenPage(key, page));
    }

    /**
     * Sets variables of a process instance (<code>PATCH
     * /v1/process-instances/{key}/variables</code>). A variable not named keeps its value.
     *
     * @param key The process instance key
     * @param variables The variables to set; a <code>null</code> value is sent as
     *            <code>null</code>
     */
    public void patchVariables(
        final long key,
        final Map<String, Object> variables
    ) {
        final var call = new Call(PATCH, "/v1/process-instances/%d/variables".formatted(key));
        call.send(JSON, call.jsonBody(new ZenBpmWire.UpdateVariables(Objects.requireNonNull(variables))), SUCCESS);
    }

    /**
     * One page of the flow elements a process instance visited (<code>GET
     * /v1/process-instances/{key}/history</code>), in the order they were entered,
     * {@value #PAGE_SIZE} to a page. The engine orders them by the millisecond they were entered
     * and by nothing else, and no other order is offered. So entries of the same millisecond may
     * swap places between two reads, and where they meet at the end of a page, one of them may
     * come on both pages or on neither.
     *
     * @param key The process instance key
     * @param page The page, counted from 1
     * @return The page
     * @throws IllegalArgumentException Where the page is below 1, before anything is sent
     */
    public Page<HistoryEntry> history(
        final long key,
        final int page
    ) {
        // the engine answers such a page with 400, a permanent failure of whatever the adapter was
        // doing; refused here, the mistake shows up in the code which made it
        if (page < 1) {
            throw new IllegalArgumentException("A page is counted from 1, but %d was asked for".formatted(page));
        }
        final var query = new LinkedHashMap<String, Object>();
        query.put("page", page);
        query.put("size", PAGE_SIZE);
        query.put("sortBy", "createdAt");
        query.put("sortOrder", "asc");
        final var call = new Call(GET, "/v1/process-instances/%d/history".formatted(key), query);
        return call.decoded(call.send(SUCCESS), ZenBpmWire.FlowElementHistoryPage.class, read -> {
            final var items = Objects.requireNonNullElse(read.items(), List.<ZenBpmWire.FlowElementHistory>of());
            final var entries = items
                    .stream()
                    .map(ZenBpmRestClient::historyEntry)
                    .toList();
            return new Page<>(entries, read.totalCount(), isLastPage(page, items.size(), read.totalCount()));
        });
    }

    /**
     * One job (<code>GET /v1/jobs/{key}</code>).
     *
     * @param key The job key
     * @return The job, empty where the engine answers 404
     * @throws ZenBpmApiException With status 502 where the key names a partition the engine does
     *             not have: a key carries the partition which holds it
     */
    public Optional<JobRef> job(final long key) {
        final var call = new Call(GET, "/v1/jobs/%d".formatted(key));
        final var answer = call.send(SUCCESS_OR_NOT_FOUND);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(call.decoded(answer, ZenBpmWire.Job.class, ZenBpmRestClient::jobRef));
    }

    /**
     * One page of a search for jobs (<code>GET /v1/jobs</code>), ordered by key within each
     * partition of the engine. The page size applies per partition; {@link Page#totalCount()}
     * counts every match.
     *
     * @param filter Which jobs, and which page
     * @return The page
     */
    public Page<JobRef> jobs(final JobFilter filter) {
        final var query = new LinkedHashMap<String, Object>();
        query.put("jobType", filter.jobType());
        query.put("state", wireName(filter.state()));
        query.put("processInstanceKey", filter.processInstanceKey());
        sortedByKey(query, filter.page(), filter.size());
        final var call = new Call(GET, "/v1/jobs", query);
        return call.decoded(call.send(SUCCESS), ZenBpmWire.JobPartitionPage.class,
                read -> partitionedPage(read.partitions(), read.totalCount(), filter.page(), filter.size(),
                        ZenBpmRestClient::jobRef));
    }

    /**
     * The jobs of one process instance in every state (<code>GET /v1/jobs</code>), read page by
     * page to the end. The jobs of its child instances are not among them.
     *
     * @param instanceKey The key of the instance
     * @return The jobs, ordered by key within each partition of the engine
     */
    public List<JobRef> jobsOf(final long instanceKey) {
        return everyPage(page -> jobs(JobFilter
                .anyJob()
                .withProcessInstanceKey(instanceKey)
                .withPage(page)));
    }

    /**
     * Completes a job (<code>POST /v1/jobs/{key}/complete</code>). A job completed before is
     * answered like the first time.
     *
     * @param key The job key
     * @param variables The job's output variables, <code>null</code> for none
     * @throws ZenBpmApiException With status 404 where the engine knows no such job, 409 where the
     *             job no longer waits (terminated or failed)
     */
    public void completeJob(
        final long key,
        final Map<String, Object> variables
    ) {
        final var call = new Call(POST, "/v1/jobs/%d/complete".formatted(key));
        call.send(JSON, call.jsonBody(new ZenBpmWire.CompleteJob(variables)), SUCCESS);
    }

    /**
     * Fails a job (<code>POST /v1/jobs/{key}/fail</code>). With an error code it is a BPMN error
     * which an error event of the model may catch.
     *
     * @param key The job key
     * @param errorCode The BPMN error code, <code>null</code> for a failure without one
     * @param variables The variables handed to the catching event, <code>null</code> for none
     * @throws ZenBpmApiException With status 404 where the engine knows no such job, 409 where the
     *             job no longer waits
     */
    public void failJob(
        final long key,
        final String errorCode,
        final Map<String, Object> variables
    ) {
        failJob(key, JobFailure
                .withErrorCode(errorCode)
                .withVariables(variables));
    }

    /**
     * Fails a job (<code>POST /v1/jobs/{key}/fail</code>) with everything a failure may say: why,
     * how many retries the job has left, and which delivery of the job stream it belongs to.
     *
     * @param key The job key
     * @param failure The failure
     * @throws IllegalArgumentException Where the backoff is negative or not whole seconds, which the
     *             engine refuses, before anything is sent
     * @throws ZenBpmApiException With status 404 where the engine knows no such job, 409 where the
     *             job no longer waits or another delivery of it decides, 400 where the engine does
     *             not take a value of the failure, such as a delivery token it never handed out
     */
    public void failJob(
        final long key,
        final JobFailure failure
    ) {
        final var call = new Call(POST, "/v1/jobs/%d/fail".formatted(key));
        call.send(JSON, call.jsonBody(new ZenBpmWire.FailJob(failure.errorCode(), failure.message(), failure
                .variables(), failure
                        .retries(), retryBackoff(failure.retryBackoff()), failure.deliveryToken(), failure.clientId())),
                SUCCESS);
    }

    /**
     * Publishes a message (<code>POST /v1/messages</code>). The engine keeps no message nobody
     * waits for.
     *
     * @param name The message name
     * @param correlationKey The correlation key, <code>null</code> for a message start event
     * @param variables The message's variables, <code>null</code> for none
     * @throws ZenBpmApiException With status 404 where nothing waits for the message
     */
    public void publishMessage(
        final String name,
        final String correlationKey,
        final Map<String, Object> variables
    ) {
        final var call = new Call(POST, "/v1/messages");
        call.send(JSON, call.jsonBody(new ZenBpmWire.PublishMessage(name, correlationKey, variables)), SUCCESS);
    }

    /**
     * Extends the lock of a job delivered over the job stream to now plus a duration (<code>POST
     * /v1/jobs/{key}/extend-lock</code>).
     *
     * @param key The job key
     * @param clientId The client id of the job stream the job was delivered to
     * @param duration How long from now the job stays locked, rounded up to whole seconds;
     *            <code>null</code> for the lock duration of the subscription the job was delivered
     *            under
     * @return When the extended lock lapses, on the engine's clock
     * @throws IllegalArgumentException Where the duration is zero or negative, before anything is
     *             sent
     * @throws ZenBpmApiException With status 409 where the lock is not held, or is held by another
     *             client
     */
    public Instant extendJobLock(
        final long key,
        final String clientId,
        final Duration duration
    ) {
        return extendJobLock(key, clientId, duration, 0);
    }

    /**
     * Extends the lock of one delivery of a job delivered over the job stream to now plus a
     * duration (<code>POST /v1/jobs/{key}/extend-lock</code>). Where the job's lock lapsed and the
     * engine handed it to the same client again, the lock of that later delivery is not extended:
     * the engine answers 409, as for a lock another client holds.
     *
     * @param key The job key
     * @param clientId The client id of the job stream the job was delivered to
     * @param duration How long from now the job stays locked, rounded up to whole seconds;
     *            <code>null</code> for the lock duration of the subscription the job was delivered
     *            under
     * @param deliveryToken The <code>delivery_token</code> of the delivery; <code>0</code>, which
     *            no delivery carries, for whatever delivery of the job the client holds
     * @return When the extended lock lapses, on the engine's clock
     * @throws IllegalArgumentException Where the duration is zero or negative, before anything is
     *             sent
     * @throws ZenBpmApiException With status 409 where the lock of the delivery is not held, or is
     *             held by another client
     */
    public Instant extendJobLock(
        final long key,
        final String clientId,
        final Duration duration,
        final long deliveryToken
    ) {
        final var call = new Call(POST, "/v1/jobs/%d/extend-lock".formatted(key));
        final var body = call.jsonBody(new ZenBpmWire.ExtendJobLock(clientId, lockDuration(duration), deliveryToken == 0
                ? null : deliveryToken));
        return call.decoded(call.send(JSON, body, SUCCESS), ZenBpmWire.LockExtended.class,
                lock -> Instant.parse(lock.lockUntil()));
    }

    /**
     * The engine's OpenAPI document, where the engine serves it (<code>GET /v1/openapi</code>).
     * The pinned engine does not, which is why the copy this repository pins is checked against
     * the engine's files instead, see decision 18 in the repository's DECISIONS.md.
     *
     * @return The document, empty where the engine answers 404
     */
    public Optional<String> openApi() {
        final var call = new Call(GET, "/v1/openapi");
        final var answer = call.send(SUCCESS_OR_NOT_FOUND);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(new String(answer.body(), StandardCharsets.UTF_8));
    }

    /**
     * Closes the HTTP client, after the calls in flight have ended. Each of them ends within
     * <code>request-timeout</code> and a quarter of a second. A call after that throws
     * {@link IllegalStateException}: a closed client is no sign of an engine which is unavailable.
     */
    @Override
    public void close() {
        closed = true;
        http.close();
    }

    /**
     * The variables of a job as the job stream hands them over, a JSON object, decoded to the
     * types {@link org.pbinitiative.zenbpmadapter.client.api.Variables} describes, the same as the
     * variables of the REST answers.
     *
     * @param json The JSON object; empty for none
     * @return The variables, which cannot be changed
     * @throws IllegalStateException Where the bytes are no JSON object
     */
    static Map<String, Object> variablesIn(final byte[] json) {
        if (json.length == 0) {
            return Map.of();
        }
        final Map<String, Object> read;
        try {
            read = JOB_VARIABLES.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (final IOException e) {
            throw new IllegalStateException("The variables of a job are no JSON object: %s".formatted(e
                    .getMessage()), e);
        }
        return variables(Objects.requireNonNullElse(read, Map.of()));
    }

    private Page<ProcessDefinitionRef> processDefinitionsPage(
        final String bpmnProcessId,
        final boolean onlyLatest,
        final int page
    ) {
        final var query = new LinkedHashMap<String, Object>();
        query.put("bpmnProcessId", bpmnProcessId);
        query.put("onlyLatest", onlyLatest);
        sortedByKey(query, page, PAGE_SIZE);
        final var call = new Call(GET, "/v1/process-definitions", query);
        return call.decoded(call.send(SUCCESS), ZenBpmWire.ProcessDefinitionsPage.class, read -> {
            final var definitions = read
                    .items()
                    .stream()
                    .map(ZenBpmRestClient::processDefinitionRef)
                    .toList();
            return new Page<>(definitions, read.totalCount(), isLastPage(page, definitions.size(), read.totalCount()));
        });
    }

    private Page<ProcessInstanceRef> childrenPage(
        final long key,
        final int page
    ) {
        final var query = new LinkedHashMap<String, Object>();
        sortedByKey(query, page, PAGE_SIZE);
        final var call = new Call(GET, "/v1/process-instances/%d/child-processes".formatted(key), query);
        return call.decoded(call.send(SUCCESS), ZenBpmWire.ProcessInstancePage.class,
                read -> partitionedPage(read.partitions(), read.totalCount(), page, PAGE_SIZE,
                        ZenBpmRestClient::processInstanceRef));
    }

    private static <T> List<T> everyPage(final IntFunction<Page<T>> pageReader) {
        final var items = new ArrayList<T>();
        for (var page = 1;; ++page) {
            final var read = pageReader.apply(page);
            items.addAll(read.items());
            if (read.last()) {
                return items;
            }
        }
    }

    // a page is the last one where it reached the total; a short page is the last one too, for a
    // total which grew between two pages while the engine had nothing more to hand out
    private static boolean isLastPage(
        final int page,
        final int itemsRead,
        final long totalCount
    ) {
        return (itemsRead < PAGE_SIZE) || (((long) page * PAGE_SIZE) >= totalCount);
    }

    // every partition answers the same page of its own items, so the last page is the one which
    // is the last of every partition
    private static <W, T> Page<T> partitionedPage(
        final List<? extends ZenBpmWire.Partition<W>> partitions,
        final long totalCount,
        final int page,
        final int size,
        final Function<W, T> mapping
    ) {
        return new Page<>(
                partitions
                        .stream()
                        .flatMap(partition -> partition
                                .items()
                                .stream())
                        .map(mapping)
                        .toList(), totalCount, partitions
                                .stream()
                                .allMatch(partition -> (partition
                                        .items()
                                        .size() < size) || (((long) page * size) >= partition.totalCount())));
    }

    // paging without an order may hand out an item twice or never, because the engine's database
    // promises no order of its own
    private static void sortedByKey(
        final Map<String, Object> query,
        final int page,
        final int size
    ) {
        query.put("page", page);
        query.put("size", size);
        query.put("sortBy", "key");
        query.put("sortOrder", "asc");
    }

    private static String wireName(final Enum<?> state) {
        return state == null
                ? null
                : state
                        .name()
                        .toLowerCase(Locale.ROOT);
    }

    // the engine reads whole seconds only and refuses PT1.5S; rounding up never shortens the lock
    private static String lockDuration(final Duration duration) {
        if (duration == null) {
            return null;
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("A lock is extended by a positive duration, but %s was asked for"
                    .formatted(duration));
        }
        final var seconds = duration.getSeconds() + ((duration.getNano() > 0) ? 1 : 0);
        return "PT%dS".formatted(seconds);
    }

    // the engine reads ISO-8601 with whole numbers only, so PT1.5S would be refused with 400
    private static String retryBackoff(final Duration backoff) {
        if (backoff == null) {
            return null;
        }
        if (backoff.isNegative() || (backoff.getNano() != 0)) {
            throw new IllegalArgumentException("The engine waits a backoff of whole seconds, but %s was asked for"
                    .formatted(backoff));
        }
        return "PT%dS".formatted(backoff.getSeconds());
    }

    private static ProcessDefinitionRef processDefinitionRef(final ZenBpmWire.ProcessDefinition definition) {
        final var key = definition.key();
        final var bpmnProcessId = definition.bpmnProcessId();
        final var version = definition.version();
        final var versionTag = definition.versionTag();
        final var bpmnData = definition.bpmnData();
        return new ProcessDefinitionRef(key, bpmnProcessId, version, versionTag, bpmnData);
    }

    private static HistoryEntry historyEntry(final ZenBpmWire.FlowElementHistory entry) {
        final var key = entry.key();
        final var elementId = entry.elementId();
        final var elementType = entry.elementType();
        final var instanceKey = entry.processInstanceKey();
        final var createdAt = Instant.parse(entry.createdAt());
        final var completedAt = entry.completedAt() == null
                ? null
                : Instant.parse(entry.completedAt());
        return new HistoryEntry(key, elementId, elementType, instanceKey, createdAt, completedAt, entry
                .inputVariables());
    }

    private static ProcessInstanceRef processInstanceRef(final ZenBpmWire.ProcessInstance instance) {
        final var key = instance.key();
        final var definitionKey = instance.processDefinitionKey();
        final var bpmnProcessId = instance.bpmnProcessId();
        final var version = instance.version();
        final var versionTag = instance.versionTag();
        final var businessKey = instance.businessKey();
        final var state = instanceState(instance.state());
        final var variables = variables(instance.variables());
        final var parentKey = instance.parentProcessInstanceKey();
        final var processType = processType(instance.processType());
        final var createdAt = Instant.parse(instance.createdAt());
        return new ProcessInstanceRef(key, definitionKey, bpmnProcessId, version, versionTag, businessKey, state, variables, parentKey, processType, createdAt);
    }

    private static ProcessInstanceRef.State instanceState(final String state) {
        return switch (state) {
            case "active" -> ProcessInstanceRef.State.ACTIVE;
            case "completed" -> ProcessInstanceRef.State.COMPLETED;
            case "terminated" -> ProcessInstanceRef.State.TERMINATED;
            case "failed" -> ProcessInstanceRef.State.FAILED;
            default ->
                throw new IllegalArgumentException("the process instance state '%s' is unknown".formatted(state));
        };
    }

    private static ProcessInstanceRef.ProcessType processType(final String processType) {
        return switch (processType) {
            case "default" -> ProcessInstanceRef.ProcessType.DEFAULT;
            case "multiInstance" -> ProcessInstanceRef.ProcessType.MULTI_INSTANCE;
            case "subprocess" -> ProcessInstanceRef.ProcessType.SUBPROCESS;
            case "callActivity" -> ProcessInstanceRef.ProcessType.CALL_ACTIVITY;
            default -> throw new IllegalArgumentException("the process type '%s' is unknown".formatted(processType));
        };
    }

    private static JobRef jobRef(final ZenBpmWire.Job job) {
        final var key = job.key();
        final var type = job.type();
        final var state = jobState(job.state());
        final var elementId = job.elementId();
        final var elementType = job.elementType();
        final var elementInstanceKey = job.elementInstanceKey();
        final var instanceKey = job.processInstanceKey();
        final var assignee = job.assignee();
        final var inputVariables = variables(job.inputVariables());
        final var createdAt = Instant.parse(job.createdAt());
        return new JobRef(key, type, state, elementId, elementType, elementInstanceKey, instanceKey, assignee, inputVariables, createdAt);
    }

    private static JobRef.State jobState(final String state) {
        return switch (state) {
            case "active" -> JobRef.State.ACTIVE;
            case "completed" -> JobRef.State.COMPLETED;
            case "terminated" -> JobRef.State.TERMINATED;
            case "failed" -> JobRef.State.FAILED;
            default -> throw new IllegalArgumentException("the job state '%s' is unknown".formatted(state));
        };
    }

    // Jackson reads a whole number as an Integer where it fits, else as a Long or a BigInteger;
    // the api package promises a Long for every one which fits a Long. Jackson's own
    // USE_LONG_FOR_INTS would refuse a number beyond a Long instead, which the engine writes
    // without an exponent up to 1e21. Nested objects and arrays are copied unmodifiable on the way.
    private static Map<String, Object> variables(final Map<String, Object> variables) {
        final var copy = new LinkedHashMap<String, Object>();
        variables.forEach((
            name,
            value) -> copy.put(name, variableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object variableValue(final Object value) {
        return switch (value) {
            case Integer number -> number.longValue();
            case Map<?, ?> object -> variables((Map<String, Object>) object);
            case List<?> array -> {
                final var copy = new ArrayList<Object>();
                array.forEach(element -> copy.add(variableValue(element)));
                yield Collections.unmodifiableList(copy);
            }
            case null, default -> value;
        };
    }

    /**
     * Reads a field the schema requires as required in both ways: present, and not
     * <code>null</code>. Jackson's own <code>required</code> checks presence only.
     */
    private static final class RequiredMeansNotNull extends JacksonAnnotationIntrospector {

        private static final long serialVersionUID = 1L;

        @Override
        public JsonSetter.Value findSetterInfo(final Annotated annotated) {
            final var setter = super.findSetterInfo(annotated);
            if ((annotated instanceof AnnotatedMember member) && Boolean.TRUE.equals(hasRequiredMarker(member))) {
                return setter.withValueNulls(Nulls.FAIL);
            }
            return setter;
        }

    }

    /**
     * What the engine answered.
     */
    private record Answer(
        int status,
        byte[] body
    ) {
    }

    /**
     * One call of an endpoint, which knows how to name itself in an exception.
     */
    private final class Call {

        private final String method;

        private final String path;

        private final URI uri;

        Call(
            final String method,
            final String path
        ) {
            this(method, path, Map.of());
        }

        Call(
            final String method,
            final String path,
            final Map<String, Object> query
        ) {
            this.method = method;
            this.path = path;
            final var encodedQuery = query
                    .entrySet()
                    .stream()
                    .filter(parameter -> parameter.getValue() != null)
                    .map(parameter -> "%s=%s".formatted(parameter.getKey(), URLEncoder.encode(parameter
                            .getValue()
                            .toString(), StandardCharsets.UTF_8)))
                    .collect(Collectors.joining("&"));
            this.uri = URI.create(encodedQuery.isEmpty()
                    ? engineAddress + path
                    : "%s%s?%s".formatted(engineAddress, path, encodedQuery));
        }

        Answer send(final IntPredicate expected) {
            return send(null, BodyPublishers.noBody(), expected);
        }

        Answer send(
            final String contentType,
            final BodyPublisher body,
            final IntPredicate expected
        ) {
            if (closed) {
                throw new IllegalStateException("ZenBPM adapter '%s': %s %s was called after the client was closed"
                        .formatted(adapterId, method, path));
            }
            final var request = HttpRequest
                    .newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", JSON)
                    .method(method, body);
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            final var answer = exchange(request.build(), expected);
            if (!expected.test(answer.status())) {
                throw unexpected(answer);
            }
            return answer;
        }

        BodyPublisher jsonBody(final Object body) {
            try {
                return BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("ZenBPM adapter '%s': the request of %s %s cannot be written as JSON"
                        .formatted(adapterId, method, path), e);
            }
        }

        // An answer the adapter cannot read is no answer it can use, although its status says
        // what the engine did. That covers a mapping which meets a value the pinned API does not
        // know, or a null where the API promises a value, so a NullPointerException is caught too.
        <W, T> T decoded(
            final Answer answer,
            final Class<W> wireType,
            final Function<W, T> mapping
        ) {
            try {
                return mapping.apply(Objects.requireNonNull(json.readValue(answer.body(), wireType)));
            } catch (IOException | IllegalArgumentException | DateTimeException | NullPointerException e) {
                final var status = answer.status();
                final var what = "was answered with status %d, but the answer cannot be read".formatted(status);
                throw new ZenBpmUnavailableException(adapterId, status, what, e, method, path);
            }
        }

        private Answer exchange(
            final HttpRequest request,
            final IntPredicate expected
        ) {
            // kept as soon as the headers arrive, so the status survives a body which does not
            final var status = new AtomicInteger();
            final BodyHandler<byte[]> statusKept = headers -> {
                status.set(headers.statusCode());
                return BodyHandlers
                        .ofByteArray()
                        .apply(headers);
            };
            // the request's own timeout ends when the answer's headers arrive, so waiting for the
            // future bounds a body which stops arriving as well
            final var future = http.sendAsync(request, statusKept);
            try {
                final var response = future.get(requestTimeout
                        .plus(BODY_TIMEOUT_MARGIN)
                        .toNanos(), TimeUnit.NANOSECONDS);
                return new Answer(response.statusCode(), response.body());
            } catch (TimeoutException e) {
                future.cancel(true);
                throw incomplete(status.get(), expected, withinTimeout(), e);
            } catch (ExecutionException e) {
                final var reason = e.getCause() instanceof HttpTimeoutException
                        ? withinTimeout()
                        : "(%s)".formatted(e.getCause());
                throw incomplete(status.get(), expected, reason, e.getCause());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread
                        .currentThread()
                        .interrupt();
                throw incomplete(status.get(), expected, "before the calling thread was interrupted", e);
            }
        }

        private String withinTimeout() {
            return "within %s, which '%s' allows".formatted(requestTimeout,
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "request-timeout"));
        }

        // A status the call does not expect is the engine's answer, whether its body arrived or
        // not. Any other call which did not end with a whole answer is no answer the adapter can
        // use, and it names the status where one arrived.
        private RuntimeException incomplete(
            final int status,
            final IntPredicate expected,
            final String reason,
            final Throwable cause
        ) {
            if (status == 0) {
                return new ZenBpmUnavailableException(adapterId, "got no answer %s"
                        .formatted(reason), cause, method, path);
            }
            if (!expected.test(status)) {
                final var unexpected = new ZenBpmApiException(adapterId, status, null, null, method, path);
                unexpected.initCause(cause);
                return unexpected;
            }
            return new ZenBpmUnavailableException(adapterId, status, "was answered with status %d, but the body did not arrive %s"
                    .formatted(status, reason), cause, method, path);
        }

        // Only the code and the message of the engine's error are read, never the rest of the
        // body: the answer of a proxy may carry anything.
        private ZenBpmApiException unexpected(final Answer answer) {
            ZenBpmWire.Error error;
            try {
                error = Objects.requireNonNullElse(json.readValue(answer.body(), ZenBpmWire.Error.class),
                        new ZenBpmWire.Error(null, null));
            } catch (IOException e) {
                error = new ZenBpmWire.Error(null, null);
            }
            return new ZenBpmApiException(adapterId, answer.status(), error.code(), error.message(), method, path);
        }

    }

}
