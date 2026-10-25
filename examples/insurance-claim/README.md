# Insurance claim

A policyholder reports damage to their car: a dent in the door, a cracked windscreen, a bumper
after a parking accident. The insurer does not want an adjuster to look at every small claim, so
a decision table routes each claim by the policy's coverage and the damage amount:

|    Coverage     | Damage amount |   Route    |                               Why                               |
|-----------------|---------------|------------|-----------------------------------------------------------------|
| `liability`     | any           | `reject`   | a liability policy pays damage done to others, not your own car |
| `comprehensive` | up to 1 000   | `auto-pay` | small damage under a comprehensive policy is paid at once       |
| `partial`       | up to 500     | `auto-pay` | under a partial policy only very small damage is paid at once   |
| anything else   | any           | `review`   | an adjuster looks at it                                         |

An adjuster approves or rejects a claim sent for review. A paid claim gets a transfer, and a
rejected one a letter.

```
(o) Claim reported --> [Route the claim] --> <Route?> --auto-pay----------------------------+--> [Pay out the damage] --> (o) Claim paid
                       (decision table)         |                                            ^
                                                +--review--> [Review the claim] --> <Approved?> --yes
                                                |                                       |
                                                +--reject----------------------------+--no
                                                                                     v
                                                                           [Reject the claim] --> (o) Claim rejected
```

The model is
[`insurance_claim.bpmn`](springboot/src/main/resources/insurance-claim/processes/zenbpm/insurance_claim.bpmn),
and the decision table is
[`claim_routing.dmn`](springboot/src/main/resources/insurance-claim/processes/zenbpm/claim_routing.dmn),
the same files in both twins. The adapter deploys the decision with the process.

## What it shows

- **A rule the business owns lives in a decision table.** Which claims are paid at once is no
  `if` in Java. The rule lives in the table, so the claims department reads and edits one file,
  and no Java changes with it. The table is packaged with the application, and the adapter
  deploys it with the process at startup.
- **A route the gateway does not know goes to a person.** In the model, `review` is the default
  flow of the gateway after the table. A route which an edited table answers and the gateway does
  not name ends with an adjuster, not with a rejection nobody saw. For the same reason the letter
  of a claim the table rejects names no cause: the table knows why it rejected, but its answer
  stays in the engine.
- **The engine evaluates the table.** The business rule task "Route the claim" needs no Java
  method: `zenbpm:calledDecision` names the decision, and the engine evaluates it with the
  shared members of the claim as its inputs.
- **The table's answer needs an output mapping.** ZenBPM keeps a decision's result in the
  variable `resultVariable` names only inside the task, and a table whose one output is named
  answers with a context holding it. So the model maps `=routing.route` into the variable `route`
  the gateway reads. The adapter's wiki explains both, under GAPS 27 and GAPS 34 of the page
  Deviations.
- **These expressions are meant as they are.** VanillaBP recommends that a model reads one name
  and that Java computes the rest, because an expression which walks an object or compares
  values ties the model to the application's data and to the engine's expression language.
  `routing.route` and the gateway's comparisons read the table's answer, not the claim, so
  `application.yaml` accepts them with
  `accept-expressions-in-the-model: true` for this process.
- **A number reaches the engine only when it is declared.** The coverage, a text, and the
  adjuster's answer, a boolean, are `@SyncWithBPMS` and travel as they are. The damage amount is a
  number, and VanillaBP hands a number to the engine only once
  `declared-aggregate-values` in `application.yaml` names it, because every expression language
  compares numbers in its own way. A decision table is what compares it, which is the ordinary
  reason to declare a value.
- **A user task for the adjuster.** The review is answered through the REST API, as in the
  purchase approval.

## Run it

Start the engine as [the examples' README](../README.md#the-engine) says, then one of the twins:

```bash
cd examples/insurance-claim/springboot && mvn spring-boot:run
# or
cd examples/insurance-claim/quarkus && mvn quarkus:dev
```

Both answer on `http://localhost:8104`.

## Walk through it

The same steps, and those of the other ways listed below, are in
[`requests.http`](requests.http), to run one by one from the HTTP client of IntelliJ IDEA.

Report a large claim under a comprehensive policy and keep its id (`jq` only extracts it):

```bash
ID=$(curl -s -X POST localhost:8104/claims -H 'Content-Type: application/json' \
  -d '{"policyNumber": "MOT-2024-0815", "claimant": "Jane Doe", "coverage": "comprehensive", "damageAmount": 6400.00, "description": "rear-ended at a traffic light"}' | jq -r .claimId)
curl -s localhost:8104/claims/$ID
```

The table routes it to an adjuster, so the claim is `IN_REVIEW` once the user task reached the
application. The adjuster approves it:

```bash
curl -s -X POST localhost:8104/claims/$ID/review -H 'Content-Type: application/json' \
  -d '{"approved": true, "comment": "the repair estimate is plausible"}'
curl -s localhost:8104/claims/$ID
```

```json
{"claimId":"CLM-…","policyNumber":"MOT-2024-0815","coverage":"comprehensive","damageAmount":6400.00,"status":"PAID","reviewed":true,"payoutReference":"PAY-7001","rejectionReason":null}
```

This is `InsuranceClaimIT#aLargeClaimIsPaidOnceTheAdjusterApprovesIt`. The other routes:

- `comprehensive` with `480.00` is paid at once, and no adjuster sees it
  (`InsuranceClaimIT#aSmallComprehensiveClaimIsPaidWithoutAReview`), and so is `1000.00`, the
  limit itself (`InsuranceClaimIT#aComprehensiveClaimOfExactlyTheLimitIsPaidWithoutAReview`);
- `partial` with `450.00` is paid at once as well
  (`InsuranceClaimIT#aSmallPartialClaimIsPaidWithoutAReview`);
- `partial` with `900.00` goes to the adjuster, and `"approved": false` rejects it with the
  adjuster's comment (`InsuranceClaimIT#aClaimTheAdjusterRejectsIsNotPaid`);
- `liability` is rejected at once, with the letter "the claim is not covered by the terms of
  policy MOT-2024-0815" (`InsuranceClaimIT#aClaimALiabilityPolicyDoesNotCoverIsRejectedAtOnce`);
- a review of a claim which waits for none is `409 Conflict`, and a coverage the table does not
  know is `400 Bad Request` (`InsuranceClaimIT#aReviewOfAClaimWhichWaitsForNoneIsRefused`);
- a claim without a policy number, a claimant or a coverage, or with a damage amount of more than
  two decimals, is `400 Bad Request` too
  (`InsuranceClaimIT#anIncompleteOrMisstatedClaimIsRefused`);
- when several adjusters answer the same review at the same moment, one answer is accepted and
  the others are `409 Conflict`, and the claim takes the path of the accepted answer: the review
  loads the claim with a lock, so the second answer waits and then finds the review closed
  (`InsuranceClaimIT#onlyOneOfTheAnswersGivenAtOnceIsAccepted`).

In zenbpm-ui the instance of `insurance-claim__insurance_claim` shows the variables `coverage`,
`damageAmount` and `approvedByAdjuster` the claim shares, the `route` the table answered, and the
path the claim took.
