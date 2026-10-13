# Review: E1 engine test infrastructure

### 🎯 Summary

The branch implements S1.2.1 and S1.2.2 together: it pins the engine and its contracts, adds a reusable Docker test helper, and generates the Java gRPC stubs during the Maven build. The implementation meets the stories' explicit local acceptance criteria, with no technical blockers or major issues found; four non-blocking findings remain, and GitHub execution and the previously identified licensing question still need maintainer follow-through.

#### Scope and baseline

| Item | Reviewed value |
|---|---|
| Review date | 2026-10-13 |
| Repository | `pbinitiative/zenbpm-vanillabp-adapter` |
| Branch | `feature/e1-engine-test-infrastructure` |
| HEAD | `76f36b5e4cae5c8858f0593818d17af76d48e653` |
| Base | `origin/main`, `690b297f64dec4ad80636829209aa9bbbcbdf101` |
| Comparison | `git diff origin/main...HEAD`, after fetching `origin main` |
| Change size | 18 files; 5,157 additions and 15 deletions, mostly copied contracts |
| Specification | `docs/plan/epic-01-foundation.md`, S1.2.1 and S1.2.2 |
| Recommendation | Approve with nits on technical grounds; this is not a licensing sign-off |

The request names the local ZenBPM branch, but the E1 progress memory locates these stories in the Java adapter branch above. The separate `zenbpm/` Go checkout is on `main` at `bc3384b3`, with unrelated working-tree edits; those edits are outside this review and were not changed.

The E1 progress memory was read before the epic specification:
`/home/alisher/.claude/projects/-home-alisher-workspace-vanillabp-development-workspace/memory/zenbpm-adapter-e1-progress.md`.
It records this implementation commit, the pin, the logging choices, the decision not to publish the test helper, and the maintainer's instruction to flag the licensing question rather than resolve it automatically.

The review covers every changed file. For the large contract copies, provenance was checked against the exact engine Git objects rather than treating upstream API design as new adapter code. Related reactor configuration, both coverage aggregates, publication guards, platform dependency graphs, and `SuppressOutputExtension` were also inspected.

### 🛑 Blockers (Critical)

None found. Both clean reactor builds compiled and passed their tests, and no production crash, compatibility break, credential exposure, or unsafe publication behavior was identified in this change.

### ⚠️ Major Issues

None found within S1.2.1/S1.2.2. The remaining observations concern regression protection, test failure bounds, and disclosure of an already documented licensing uncertainty.

### 💡 Suggestions (Minor)

#### 1. Bound the integration test's HTTP requests

**Location:** `engine-test-support/src/test/java/org/pbinitiative/zenbpmadapter/test/EngineUnderTestIT.java:37,88–97`

The shared HTTP client has no explicit connect timeout, and neither GET request has a request timeout. The container's two-minute startup timeout bounds the readiness wait only; it does not bound these subsequent `HttpClient.send` calls. If the engine accepts a connection but stops answering after startup, this small smoke test can stall until an external timeout intervenes. The GitHub job allows 45 minutes, and an IDE run has no comparable bound here.

**Smallest fix:** set a connect timeout on the client and a request timeout on the request builder. A short, explicit limit appropriate to these local health/status calls will turn a stall into a useful test failure. Closing the client after the class also makes ownership of its resources explicit.

**Evidence:** direct inspection of the client and request construction. No deliberate engine-hang reproduction was performed.

#### 2. Validate that the configured commit is a full Git hash

**Location:** `engine-test-support/src/main/java/org/pbinitiative/zenbpmadapter/test/EngineUnderTest.java:146–156`; `engine-test-support/src/test/java/org/pbinitiative/zenbpmadapter/test/EngineUnderTestIT.java:79–84`

The helper rejects missing, blank, and unfiltered properties, but accepts an abbreviated `engine.commit`. The integration test then accepts it when it equals the engine's reported abbreviation. This leaves the documented requirement for a full 40-character pin unenforced, despite the current POM containing the correct full hash.

**Reproduction:** `mvn -B --no-transfer-progress -pl engine-test-support verify -Dzenbpm.commit=bdd795a399b6` passed all three integration tests. A deliberately unrelated 40-character commit failed as intended, so this is a format-validation gap rather than a broken comparison for the current pin.

**Smallest fix:** validate `engine.commit` against `[0-9a-f]{40}` before using it, and cover abbreviated and malformed values with a regression test. Keep the prefix comparison for the engine's shortened report; that comparison is explicitly required by the story.

#### 3. Make contract provenance checks repeatable

**Location:** `core/src/main/zenbpm/api.yaml:1`; `core/src/main/proto/zenbpm.proto:1–9`; `core/src/test/java/org/pbinitiative/zenbpmadapter/client/grpc/ZenBpmGrpcStubsTest.java:29–35`

The copies are correct today: the OpenAPI file matches the pinned engine byte for byte below its provenance header, and the proto matches after removing its header and the declared `java_package` addition. However, the committed tests check the container pin and the `JobStream` descriptor, not whether the copied contracts still correspond to that pin. An accidental REST schema edit, or a proto field change that preserves `JobStream`, would not be caught by those assertions.

Decision 3 also promises a contract-diff test. The plan records one-time `cmp`/`diff` evidence, but that is not a guard future builds can rerun automatically.

**Smallest fix:** add a repeatable provenance check against the exact upstream commit, allowing only the documented proto transformation. This can be a CI check or an explicit verification script so ordinary offline builds do not acquire an unexpected dependency on an engine checkout. At minimum, verify that both headers agree with the POM's commit and image properties.

**Severity rationale:** no present contract drift was found, and the two stories' explicit acceptance criteria require correct copies, which this branch supplies. This is a non-blocking regression-protection gap, not evidence that the current generated client is incompatible.

#### 4. Keep the licensing uncertainty visible in the public-facing claims

**Location:** `NOTICE:21–27`; related claims in `README.md:235–243` and `pom.xml:17–30`

The new NOTICE paragraph correctly flags that the copied engine interface files and generated gRPC classes have an unresolved distribution-license question. The broader statements still say that every other file is MIT and that MIT is the whole truth for published code. The core jar and its sources jar now contain generated classes derived from the copied engine proto, so those statements are stronger than the new NOTICE supports.

**Smallest fix now:** qualify the README and parent-POM explanation to explicitly acknowledge the unresolved exception and point to NOTICE. Do not select a new license or change the decision log without maintainer approval.

**Maintainer follow-through:** obtain the distribution decision before treating this review as permission to publish those files and generated artifacts under the current license claims. This review does not determine the legal status of generated protocol bindings or assert that the entire adapter must be AGPL.

**Severity rationale:** this is the risk already recorded in the E1 memory and open question 13, with an explicit instruction to flag it only. It is not escalated into an invented technical blocker.

#### Story-by-story acceptance assessment

##### S1.2.1: pin the engine contract and start it from a test

| Requirement | Assessment and evidence |
|---|---|
| Pin a suitable engine `main` build by full commit and digest | Met. The POM pins `bdd795a399b6484d062d52ac90c0d0725cb0ad91` and image digest `sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0`. The image's full OCI revision label matches the commit. The memory and plan record that it was the engine head at implementation time. |
| Use that exact commit for both contract copies | Met. Compared each file against `git show <pin>:<upstream-path>`. OpenAPI is unchanged below its one-line header; the proto has only its four-line header and `java_package` addition. |
| Publish the resolved engine commit in adapter metadata | Met. The built core jar's `META-INF/vanillabp/adapter-zenbpm.properties` contains the full resolved `zenbpm.engine`, with no placeholder. |
| Reusable `engine-test-support` module | Met. The helper takes the image from a filtered classpath resource, exposes REST and gRPC ports, configures the requested environment, and waits for HTTP readiness with a bounded startup timeout. |
| Preserve finished-instance history with TTL zero | Met. The pinned engine only configures instance-history TTL when the value is nonzero; the helper's comment correctly explains zero as retaining history. |
| Ready, image, and commit assertions in the first IT | Met. Three Docker integration tests passed in both clean builds. The commit assertion uses the reported shortened commit, not the release version. See finding 2 for the missing format guard. |
| Skip rather than fail when Docker is absent | Met. A full build in an isolated mount namespace with `/run` hidden skipped all three engine tests and completed successfully. The host Docker service was not stopped. |
| Capture engine logs for failed tests | Met in this module. A wrong-pin run failed and replayed the container creation line and 113 DEBUG records from `zenbpm-engine`. The normal build did not replay those engine records. |
| Logging template | Implemented with a documented deviation: root INFO, not explicit WARN levels for the three named loggers. This preserves diagnostic INFO records for failure replay; the failing-run check supports the explanation. |
| Decide registry login policy for snapshot publication | Met. The publication workflow keeps anonymous pulls for the public engine image and does not persist a package-write token in Docker configuration. Actual hosted-run behavior remains to be checked after pushing. |
| README explains the tested-only pin and upgrade procedure | Met. It names the commit and digest, the `main`-build status, and the requirement to update both contracts when moving the pin. |
| Test helper omitted from published artifacts | Met. It is installed locally, skipped for deployment, and named as a test-only coverage exception. The deployment safety check still finds exactly 13 published files. |

##### S1.2.2: generate gRPC stubs from the pinned proto

| Requirement | Assessment and evidence |
|---|---|
| Generate Java and gRPC sources during the build | Met. Ascopes plugin 5.1.11 invokes Maven-resolved `protoc` 4.35.0 and the gRPC generator 1.81.0. Clean builds recreated `target/generated-sources/protobuf`. No system `protoc` is on PATH. |
| Use the adapter's generated-code package | Met. The only proto option addition sets `org.pbinitiative.zenbpmadapter.client.grpc`; the generated classes use that package. |
| Keep generated sources out of formatting and version control | Met. Spotless checks passed, generated files remain under `target`, and no generated target files are tracked. |
| Exclude stub classes from coverage | Met. Both platform aggregate reports have no measurable instructions at this skeleton stage; their threshold tests report skipped rather than claiming a percentage. The thresholds remain 85, with a target of 90. |
| Test the `JobStream` descriptor | Met. `ZenBpmGrpcStubsTest` passed and checks both the full RPC name and bidirectional-streaming method type. |
| Keep the Quarkus dependency graph usable | Met for this story. The full reactor builds on Java 21 and Java 25, and a Java 21 class-loading probe using the Quarkus runtime dependency graph successfully loaded `ZenBpmGrpc` and `Zenbpm.JobStreamRequest`. It resolved gRPC 1.81.0 and protobuf 4.35.0. This is not a native-image or booted-application test. |

#### Validation performed

| Check | Result |
|---|---|
| `mvn -B --no-transfer-progress clean install`, Java 25.0.2 | Passed, about 19 seconds. All 12 reactor projects succeeded. |
| Same clean build with Java 21.0.12.1 | Passed, about 21 seconds. All 12 reactor projects succeeded. |
| Test counts in each clean build | 24 tests reported: 3 engine ITs, 1 stub unit test, and 20 gate/convention/publication tests. No failures or errors; 2 empty-report coverage threshold tests intentionally skipped. |
| `bin/check-deploy-safety.sh` | Passed. Green deploy produced exactly 13 files; the deliberately red coverage gate deployed nothing. All deployments were into temporary local directories, not GitHub Packages. |
| Wrong full commit override | Expected failure: 1 failing engine IT out of 3. Diagnostic replay included the engine log. |
| Abbreviated commit override | Unexpected acceptance of an invalid pin format: all 3 ITs passed. Recorded as finding 2. |
| Full `mvn install` without access to the Docker socket | Passed. All 3 engine ITs skipped; the stub and gate tests still ran. |
| Contract comparisons against pinned engine Git objects | Passed for both copies, with only the documented proto changes. |
| Docker image revision inspection | Full OCI revision label equals the POM's full commit. |
| Built metadata and source packaging | Resolved adapter metadata inspected. Generated Java sources are included in the core sources jar; the proto is not embedded in the runtime jar. |
| Java 21 loading through Quarkus runtime dependencies | Passed; descriptors reported `grpc.ZenBpm/JobStream` and `grpc.JobStreamRequest`. |
| Working-tree preservation | No implementation, test, POM, or workflow source edits were made. The report is the only new repository file. Property overrides were followed by a normal build restoring the canonical filtered values. |

Local diagnostic logs are in `/tmp/zenbpm-e1-review-*.log`, including the clean builds, deployment check, wrong-pin run, abbreviated-pin run, and no-Docker run. These temporary logs are not committed and may be removed by the operating system; the results above are the durable review evidence.

#### Boundaries and remaining evidence

- The branch has not been pushed as part of this review. No new pull request, hosted check, or snapshot publication was started, so no CI run IDs can be certified here. Add the hosted evidence after pushing, as the E1 memory already requests.
- Builds used the existing local Maven repository containing the upstream VanillaBP snapshots. These checks are clean source builds, not empty-Maven-cache or fresh-fork demonstrations.
- Docker validation ran on Linux amd64. Other operating systems, architectures, and remote Docker hosts were not exercised.
- The absence of measurable platform coverage is expected for this foundation story. It is not evidence that future REST clients, deployment services, workers, or platform wiring are tested.
- Native images, a live gRPC job-stream exchange, REST client behavior, engine compatibility warnings at application startup, and end-to-end workflow execution belong to later stories. Their absence is not reported as a defect in S1.2.1/S1.2.2.
- No Go engine code, SQL, Raft persistence, or gRPC server implementation changes are part of this adapter diff. Go-specific review rules were therefore not applied to Java helper code.

The explicit local acceptance criteria for these two stories are satisfied. The technical merge recommendation is approve with nits, with hosted-run evidence still outstanding and distribution-license approval left to the maintainers.
