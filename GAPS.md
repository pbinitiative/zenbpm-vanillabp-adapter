# Gaps

What the ZenBPM engine cannot do that VanillaBP's adapter contract asks for, the evidence for it in
the engine's source (paths relative to the root of `pbinitiative/zenbpm`), and how this adapter
behaves instead. Once the user documentation is written, users read the same list, one sentence
per gap, on the wiki page `Deviations`.

The numbers were handed out by the implementation plan in [`docs/`](docs/README.md), so every story
which meets a gap cites the same number. An entry is added here by the story which first makes it
true; that is why numbers are missing below. The drafts of the missing ones are in
[`docs/architecture/03-deviations-and-gaps.md`](docs/architecture/03-deviations-and-gaps.md).

What the engine lacks is true today. How the adapter answers it is marked *planned* until the code
doing it is merged, together with the test which holds it.

| #  |                                               Gap                                                |                            Evidence                             |                                                                    Adapter behaviour                                                                    |        Engine change which would close it         |
|----|--------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| 11 | No tenant or namespace                                                                           | no such field anywhere                                          | *planned:* `by-adapter` stays the default and is refused while deploying; every application configures `use-prefix` or `none` (one line per adapter id) | none planned                                      |
| 12 | No authentication and no TLS on the public ports                                                 | `internal/rest/server.go` middleware chain; `grpc.NewServer()`  | no `auth.*` keys; *planned:* the wiki names a proxy in front of the engine                                                                              | authentication and TLS on the REST and gRPC ports |
| 13 | No signals                                                                                       | `pkg/bpmn/model/bpmn20/events.go` `TUnsupportedEventDefinition` | *planned:* `SEND_SIGNAL` is not offered, and a model with a signal event is refused before deployment                                                   | none planned                                      |
| 14 | No conditional, escalation or compensation events, no script or manual tasks, no complex gateway | `pkg/bpmn/unsupported_elements_test.go`, docs matrix            | *planned:* refused before deployment, naming the element ids                                                                                            | none                                              |
| 18 | One executable process per file                                                                  | `pkg/bpmn/model/bpmn20/core.go` single `Process`                | *planned:* a file with two executable processes is refused, naming both                                                                                 | none                                              |
| 21 | No API stability promise                                                                         | 1.8 changed the deploy content type                             | one pinned engine version per release (decision 15)                                                                                                     | contract tests against the pinned image           |

