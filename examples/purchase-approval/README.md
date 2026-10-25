# Purchase approval

An employee wants to buy something for work: a laptop, a training course, a machine for the
workshop. A clerk of the purchasing department checks the request. Above 50 000 a manager has to
approve it as well. An approved request becomes a purchase order in the purchasing system; a
rejected one is reported back to the employee with the reason.

The model is derived from the showcase process of the ZenBPM engine, with the lanes Clerk and
Manager and the gateway on the amount, and adds what a real department needs around them: a way
to reject, and the steps which place the order or tell the requester.

```
Clerk     (o)--[Check purchase request]--<in order?>--yes--<above 50 000?>--no-----------+
                                              |                 |                        |
Manager                                       |                yes                       |
                                              |                 |                        |
                                              |       [Approve high-value purchase]      |
                                              |                 |                        |
                                              |           <approved?>--yes---------------+
                                              no                no                       |
                                              |                 |                        v
Purchasing                                    +-----------------+-->[Notify the     [Place purchase
system                                                               requester]--(o)  order]--(o)
```

The model is
[`purchase_approval.bpmn`](springboot/src/main/resources/purchase-approval/processes/zenbpm/purchase_approval.bpmn),
the same file in both twins.

## What it shows

- **The workflow aggregate is the data.** `PurchaseRequest` is a JPA entity and the only place
  the request lives; there are no process variables. VanillaBP saves it and starts the workflow
  in one transaction.
- **The engine sees only what the gateways decide on.** The class is `@NoSyncWithBPMS`, and three
  booleans are `@SyncWithBPMS`: the clerk's answer, the manager's answer, and
  `isAboveApprovalLimit()`. The limit of 50 000 is a rule of the company, so it is written in
  Java, and the model only asks `=aboveApprovalLimit`. VanillaBP refuses to hand the amount itself
  to a gateway, because every expression language compares numbers in its own way.
- **A user task is a notification.** The adapter finds the open user tasks
  `checkRequest` and `approveHighValue` by their `zenbpm:taskDefinition type`, and calls the
  method of the same name in `WorkflowTaskHandler`, which keeps the task's id on the aggregate.
  The REST API later answers the task with that id through `ProcessService#completeUserTask`.
  The adapter looks for new user tasks every `user-task-poll-interval`, which `application.yaml`
  sets to one second.
- **A service task is code.** `placeOrder` and `notifyRejection` run in the application. The
  model gives each of them three attempts (`retries="3"`), so a purchasing system which is down for
  a moment does not stop the process.

The classes follow the VanillaBP blueprints: `ApiController` is the REST API, `PurchaseApproval`
the business code, `Workflow` tells the process what happened, and `WorkflowTaskHandler` is what
the process calls.

## Run it

Start the engine as [the examples' README](../README.md#the-engine) says, then one of the twins:

```bash
cd examples/purchase-approval/springboot && mvn spring-boot:run
# or
cd examples/purchase-approval/quarkus && mvn quarkus:dev
```

Both answer on `http://localhost:8101`.

## Walk through it

The same steps, and those of the other ways listed below, are in
[`requests.http`](requests.http), to run one by one from the HTTP client of IntelliJ IDEA.

Submit a request above the limit and keep its id (`jq` only extracts it):

```bash
ID=$(curl -s -X POST localhost:8101/purchase-requests -H 'Content-Type: application/json' \
  -d '{"requester": "Jane Doe", "item": "CNC milling machine", "amount": 75000}' | jq -r .id)
curl -s localhost:8101/purchase-requests/$ID
```

```json
{"id":"…","requester":"Jane Doe","item":"CNC milling machine","amount":75000,"status":"IN_CHECK","comment":null,"purchaseOrderNumber":null,"requesterNotified":false}
```

The status is `SUBMITTED` for the second until the user task reaches the application, then
`IN_CHECK`. The clerk finds the request in order:

```bash
curl -s -X POST localhost:8101/purchase-requests/$ID/check -H 'Content-Type: application/json' \
  -d '{"approved": true, "comment": "budget confirmed"}'
```

The amount is above the limit, so the request waits for the manager (`IN_HIGH_VALUE_APPROVAL`),
who approves it:

```bash
curl -s -X POST localhost:8101/purchase-requests/$ID/high-value-approval -H 'Content-Type: application/json' \
  -d '{"approved": true, "comment": "fits the investment plan"}'
curl -s localhost:8101/purchase-requests/$ID
```

```json
{"id":"…","requester":"Jane Doe","item":"CNC milling machine","amount":75000,"status":"ORDERED","comment":"fits the investment plan","purchaseOrderNumber":"PO-4501","requesterNotified":false}
```

This is `PurchaseApprovalIT#aPurchaseAboveTheLimitIsOrderedOnlyOnceTheManagerApprovesIt`. The
other ways a request goes:

- an amount up to 50 000 is ordered after the clerk's check alone
  (`PurchaseApprovalIT#aSmallPurchaseTheClerkFindsInOrderIsOrdered`);
- `"approved": false` of the manager ends in `REJECTED`, and the requester is told
  (`PurchaseApprovalIT#aPurchaseTheManagerRejectsIsReportedToTheRequester`);
- `"approved": false` of the clerk ends there, and the manager never sees the request
  (`PurchaseApprovalIT#aRequestTheClerkRejectsNeverReachesTheManager`);
- an answer for a task which is not open, such as the manager's approval of a small purchase, is
  refused with `409 Conflict` (`PurchaseApprovalIT#anAnswerForATaskWhichIsNotOpenIsRefused`), and
  an unknown id is `404 Not Found` (`PurchaseApprovalIT#anUnknownRequestIsNotFound`);
- answers to the same task which arrive at the same time decide once: an answer loads the request
  with a write lock, so the others wait, find the task answered and get `409 Conflict`, and the
  request ends the way the accepted answer said
  (`PurchaseApprovalIT#answersGivenAtTheSameTimeDecideOnce`);
- a request without a requester or an item, or with an amount which is not positive, is refused
  with `400 Bad Request` (`PurchaseApprovalIT#anInvalidRequestIsRefused`).

In zenbpm-ui the instances are those of `purchase-approval__purchase_approval`, each with the
request's id as its business key: you see the token wait at the user task, and the path it took
once the request ended. Its variables are the three booleans and the request's `id`, which the
adapter shares to find the workflow again; the requester, the item and the amount never leave the
application.
