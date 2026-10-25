# Employee onboarding

Jane Doe signed her contract as a backend developer, and she starts on the second of November.
Before that day IT creates her accounts, and her workplace has to be ready: IT orders a laptop,
and facility management issues the badge that opens the doors. The two departments work at the
same time. On her first day HR holds the welcome meeting, and with that she is onboarded.

Preparing a workplace is not only part of an onboarding. When an employee moves to another site,
the same laptop and badge are needed. So the workplace is prepared by a process of its own,
`workplace_provisioning`, which the onboarding calls, and which any other process can call too.

```
employee_onboarding
(o) Contract signed --> [Create the accounts] --> [[Provision the workplace]] --> [Hold the welcome meeting] --> (o) Employee onboarded
                                                          |
workplace_provisioning                                    v
                         (o) Workplace requested --> <+> --> [Order the laptop] --> <+> --> (o) Workplace ready
                                                      |                              ^
                                                      +---> [Issue the badge] -------+
```

The models are
[`employee_onboarding.bpmn`](springboot/src/main/resources/employee-onboarding/processes/zenbpm/employee_onboarding.bpmn)
and
[`workplace_provisioning.bpmn`](springboot/src/main/resources/employee-onboarding/processes/zenbpm/workplace_provisioning.bpmn),
the same files in both twins. They are two files because the engine takes one process per file.

## What it shows

- **A call activity on the same aggregate.** "Provision the workplace" is a call activity with
  `zenbpm:calledElement processId="workplace_provisioning"`. The called process works on the
  onboarding of its caller. `WorkflowTaskHandler` names the onboarding as its process and the
  provisioning as a `secondaryBpmnProcesses` entry, and serves the tasks of both. Only the
  onboarding is ever started by the application.
- **Two branches, one row.** The parallel gateway of the provisioning delivers "Order the laptop"
  and "Issue the badge" at the same moment. Each task loads the onboarding, writes its own column
  and saves it, in a transaction of its own. Without `@DynamicUpdate` on the aggregate Hibernate
  writes every column. Then the transaction which commits second puts back the empty column of
  the first, and the laptop or the badge is lost, on every run against the engine
  (`EmployeeOnboardingIT#theCalledProcessPreparesTheWorkplaceWithBothBranchesKept`). With it each
  transaction writes only what it changed. VanillaBP's report at startup still names the
  aggregate, because it looks only for a version attribute (`@Version`). That is the other way
  out: here it would make one of the two branches fail and run again in every onboarding,
  because they always write at the same moment.
- **The end of the workflow.** A `@WorkflowEnded` method tells the application that the
  onboarding is done. The engine reports no end of a workflow, so the adapter puts a marker task
  in front of the end event of the onboarding when it deploys the model. The called process gets
  no marker: its end is not the end of the workflow.
- **A user task for HR**, answered through the REST API, as in the purchase approval. The answer
  locks the onboarding while it reads it. So when two people of HR report the same meeting at
  once, the second waits for the first, finds no meeting due and gets `409 Conflict`: one report
  answers the task, and its notes are the ones kept
  (`EmployeeOnboardingIT#aWelcomeMeetingReportedSeveralTimesAtOnceIsAcceptedOnce`).

## Run it

Start the engine as [the examples' README](../README.md#the-engine) says, then one of the twins:

```bash
cd examples/employee-onboarding/springboot && mvn spring-boot:run
# or
cd examples/employee-onboarding/quarkus && mvn quarkus:dev
```

Both answer on `http://localhost:8105`.

## Walk through it

The same steps, and those of the other ways listed below, are in
[`requests.http`](requests.http), to run one by one from the HTTP client of IntelliJ IDEA.

The contract is signed (`jq` only extracts the id):

```bash
ID=$(curl -s -X POST localhost:8105/onboardings -H 'Content-Type: application/json' \
  -d '{"employeeName": "Jane Doe", "position": "backend developer", "startDate": "2026-11-02"}' | jq -r .onboardingId)
curl -s localhost:8105/onboardings/$ID
```

A few seconds later the accounts exist, the workplace is ready, and HR has to hold the welcome
meeting:

```json
{"onboardingId":"ONB-…","employeeName":"Jane Doe","position":"backend developer","startDate":"2026-11-02","status":"WELCOME_MEETING_DUE","emailAddress":"jane.doe@example.com","laptopSerialNumber":"NB-30001","badgeNumber":"BADGE-501","welcomeMeetingNotes":null}
```

HR holds it:

```bash
curl -s -X POST localhost:8105/onboardings/$ID/welcome-meeting -H 'Content-Type: application/json' \
  -d '{"notes": "met the team, got the keys"}'
curl -s localhost:8105/onboardings/$ID
```

The status is `ONBOARDED`, set by the `@WorkflowEnded` method, and the application logs
"welcome aboard" (`EmployeeOnboardingIT#aNewEmployeeIsOnboardedOnceTheWelcomeMeetingIsHeld`). A
welcome meeting reported a second time is `409 Conflict`, and an unknown id `404 Not Found`
(`EmployeeOnboardingIT#aWelcomeMeetingWhichIsNotDueIsRefused`). A new hire without a name, a
position or a first working day is `400 Bad Request`
(`EmployeeOnboardingIT#anIncompleteNewHireIsRefused`).

In zenbpm-ui the onboarding is an instance of `employee-onboarding__employee_onboarding` with the
onboarding's id as its business key. The provisioning is an instance of
`employee-onboarding__workplace_provisioning` whose parent is that one.
