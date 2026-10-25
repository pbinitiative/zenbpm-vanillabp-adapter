# Card payment

A customer of a web shop pays an order by card. The shop asks its payment provider to charge the
card, and once the money is there it confirms the order and hands it to the warehouse. Three things
can get in the way, and the shop answers each of them differently:

- **The card is declined**, because there is not enough money on it or the bank suspects fraud.
  That is an answer about the card, and charging it again would not change it. The shop asks the
  customer to pay with another card.
- **The provider does not answer once**, a timeout or a hiccup in its network. That says nothing
  about the card. The shop tries again two seconds later, and the customer never notices
  (`CardPaymentIT#aProviderWhichDoesNotAnswerOnceIsTriedAgain`).
- **The provider is down** for longer than the attempts last. The process stops at the charge
  with an incident, an operator sees it in zenbpm-ui, and once the provider is back the operator
  resolves the incident and the payment goes through.

```
(o) Order placed --> [Charge the card] --> [Confirm the order] --> (o) Order paid
                          |
                     (x) card declined
                          |
                          +--> [Ask the customer for another card] --> (o) Payment declined
```

The model is
[`card_payment.bpmn`](springboot/src/main/resources/card-payment/processes/zenbpm/card_payment.bpmn),
the same file in both twins.

## What it shows

- **A BPMN error is a business answer.** The service task `chargeCard` throws a `TaskException`
  with the code `card-declined` when the provider declines the card. The error boundary event of
  the task catches it by that code, and the process takes the path for a declined card. What the
  task wrote to the payment before, the reason of the decline, is kept.
- **Any other exception is a failure, and the engine retries it.** The model gives the task three
  attempts (`zenbpm:taskDefinition retries="3"`). On a failure VanillaBP rolls back what the task
  wrote, and the engine hands the task out again after the `retry-backoff` of `application.yaml`,
  two seconds (`CardPaymentIT#aProviderWhichDoesNotAnswerOnceIsTriedAgain` measures the gap). After
  the last attempt the engine raises an incident.
- **An incident is for an operator.** Resolving it gives the task a fresh series of three attempts.
- **Nothing of the payment leaves the application.** The model decides nothing on data, so the
  aggregate is `@NoSyncWithBPMS` without a single shared member. The engine knows the order's
  number only, which it shows as the business key. The amount and the card stay in the shop.
- **The provider is behind an interface.** `PaymentProvider` is what a real shop implements with
  the client of Stripe or Adyen. `LocalPaymentProvider` simulates it and knows the test cards
  such providers offer.

|      Card token       |              What the simulated provider does               |
|-----------------------|-------------------------------------------------------------|
| `tok_visa`            | charges the card                                            |
| `tok_chargeDeclined`  | declines the card: insufficient funds                       |
| `tok_processingError` | does not answer the first attempt of an order, then charges |

`PUT /simulation/payment-provider` with `{"available": false}` takes the simulated provider down
for every card, and `{"available": true}` brings it back. It belongs to the simulation, not to the
shop.

## Run it

Start the engine as [the examples' README](../README.md#the-engine) says, then one of the twins:

```bash
cd examples/card-payment/springboot && mvn spring-boot:run
# or
cd examples/card-payment/quarkus && mvn quarkus:dev
```

Both answer on `http://localhost:8102`.

## Walk through it

The same steps, and those of the other ways listed below, are in
[`requests.http`](requests.http), to run one by one from the HTTP client of IntelliJ IDEA.

Pay an order with a valid card:

```bash
curl -s -X POST localhost:8102/payments -H 'Content-Type: application/json' \
  -d '{"orderId": "ORD-1001", "customerEmail": "jane.doe@example.com", "amount": 49.90, "cardToken": "tok_visa"}'
curl -s localhost:8102/payments/ORD-1001
```

```json
{"orderId":"ORD-1001","amount":49.90,"status":"PAID","transactionId":"ch_…","declineReason":null,"orderConfirmed":true,"customerAskedForAnotherCard":false}
```

This is `CardPaymentIT#aValidCardIsChargedAndTheOrderConfirmed`. With `tok_chargeDeclined` the
payment ends `DECLINED` with the reason, and the application logs the mail which asks for another
card (`CardPaymentIT#aDeclinedCardMakesTheShopAskForAnotherOne`). With `tok_processingError` the
log shows two attempts two seconds apart, and the payment ends `PAID` all the same
(`CardPaymentIT#aProviderWhichDoesNotAnswerOnceIsTriedAgain`). Paying the same order twice is
refused with `409 Conflict` (`CardPaymentIT#aSecondPaymentOfTheSameOrderIsRefused`). That holds
for two payments sent at the same moment too: the payment is inserted before its workflow starts,
the order's number is its primary key, and the database lets one of them in, so the card is
charged once (`CardPaymentIT#paymentsOfTheSameOrderAtTheSameTimeChargeOnce`).

A payment which cannot be charged as it is gets `400 Bad Request`, and nothing is stored. That is
an amount which is not positive or has more than two decimals, or a missing e-mail address or card
token. It is also an order number which is not 1 to 40 letters, digits and dashes. The order number is
part of the payment's address, `/payments/{orderId}`
(`CardPaymentIT#anInvalidPaymentIsRefused`).

### An outage

Take the provider down and pay an order:

```bash
curl -s -X PUT localhost:8102/simulation/payment-provider -H 'Content-Type: application/json' -d '{"available": false}'
curl -s -X POST localhost:8102/payments -H 'Content-Type: application/json' \
  -d '{"orderId": "ORD-1002", "customerEmail": "jane.doe@example.com", "amount": 129.00, "cardToken": "tok_visa"}'
```

The log shows three attempts two seconds apart, and the payment stays `PENDING`
(`CardPaymentIT#anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves`). In zenbpm-ui the
instance of `card-payment__card_payment` with the business key `ORD-1002` has an incident at
"Charge the card", whose message names the attempts and the exception. Bring the provider back:

```bash
curl -s -X PUT localhost:8102/simulation/payment-provider -H 'Content-Type: application/json' -d '{"available": true}'
```

Nothing happens yet: the engine does not try again on its own once the attempts are spent.
Resolve the incident in zenbpm-ui, or with the engine's REST API (`jq` 1.7 or newer keeps the
engine's 64-bit keys exact):

```bash
INSTANCE=$(curl -s 'localhost:8080/v1/process-instances?businessKey=ORD-1002' | jq -r '.partitions[].items[].key')
INCIDENT=$(curl -s "localhost:8080/v1/process-instances/$INSTANCE/incidents?state=unresolved" | jq -r '.items[].key')
curl -s -X POST localhost:8080/v1/incidents/$INCIDENT/resolve
curl -s localhost:8102/payments/ORD-1002
```

The task gets a fresh series of attempts, the first one charges the card, and the payment ends
`PAID` (`CardPaymentIT#anOutageLongerThanTheAttemptsIsAnIncidentAnOperatorResolves`).
