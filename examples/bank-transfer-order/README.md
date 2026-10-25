# Bank transfer order

A customer orders a pair of hiking boots and chooses to pay by bank transfer. The shop keeps the
boots aside and mails the payment instructions: the account, the amount, and the order's number
as the reference. Then it waits. When the bank reports a transfer with that reference and the
right amount, the shop ships. A customer who has not paid after a while gets a reminder. An order
still unpaid at the deadline is cancelled, and the boots go back on the shelf.

```
(o) Order placed --> [Send the payment instructions] --> [Wait for the bank transfer] --> [Ship the order] --> (o) Order shipped
                                                            :              |
                                              (reminder due, PT10S)   (deadline, PT25S)
                                                            :              |
                                    [Send a payment reminder] --> (o)   [Cancel the order] --> (o) Order cancelled
```

The model is
[`bank_transfer_order.bpmn`](springboot/src/main/resources/bank-transfer-order/processes/zenbpm/bank_transfer_order.bpmn),
the same file in both twins. A real shop waits days: it would remind after seven days (`P7D`) and
cancel after fourteen (`P14D`). The example waits ten and twenty-five seconds, so you can watch both
timers fire. The engine takes a timer's duration only as a literal value, not as an expression of
the order, so the numbers are in the model.

## What it shows

- **Waiting is the engine's job.** The receive task "Wait for the bank transfer" holds the
  workflow until the message `PaymentReceived` arrives. No code runs while the shop waits.
- **The application correlates the message.** The bank's webhook `POST /bank/transfers` reports
  every transfer. Only a transfer whose reference is an order waiting for its money, and whose
  amount matches, becomes `ProcessService#correlateMessage(order, "PaymentReceived")`. Anything
  else is answered `422` and left to accounting. A transfer for a cancelled order is `409`: it
  is paid back. A report without a reference or a positive amount is `400`
  (`BankTransferOrderIT#anIncompleteTransferIsRefused`).
- **The deadline and the transfer can meet.** The engine fires the deadline on its own clock, so a
  transfer can be accepted after the deadline fired and before the cancellation is saved. The
  wait is over then, and the order cannot be shipped any more. The cancellation sees that the order
  is paid: the order ends `REFUND_DUE` instead of `CANCELLED`, the stock goes back, and the mail
  tells the customer that the money is paid back
  (`BankTransferOrderIT#aTransferWhichArrivesWithTheDeadlineIsPaidBack`). In the same way the
  reminder is not sent to a customer whose transfer arrived just before it was due
  (`BankTransferOrderIT#aCustomerWhoHasJustPaidIsNotReminded`).
- **No correlation key in the model.** The message names no `zenbpm:subscription`. The adapter
  gives every message a workflow waits for the id of its aggregate, the order's number, as its
  correlation key. So the message reaches the one order it is for.
- **Two timers on one wait.** The reminder is non-interrupting (`cancelActivity="false"`): it sends
  the reminder on a branch of its own, and the order keeps waiting. The deadline interrupts: it
  ends the waiting and cancels the order.
- **Two branches, one order.** While the reminder is sent, the transfer or the deadline can
  arrive, and two transactions write the same order. `Order` has a version attribute
  (`@Version`), so the transaction committing second fails instead of putting back what it read
  before the other one committed. A task which fails like that is handed out again by the engine,
  and it reads the order anew. When the transaction of the bank's webhook is the one which fails,
  the bank gets an error back and sends the transfer again, as banks do with a webhook which
  fails.
- **A task can run twice, the warehouse ships once.** A task handed out again calls the warehouse
  again. So the order's number is the warehouse's idempotency key: shipping an order a second
  time answers with the parcel of the first
  (`BankTransferOrderIT#aShipmentRepeatedAfterAFailureSendsOneParcel`).
- **Two deliveries, one payment.** Banks deliver a webhook twice now and then. The order is locked
  while a transfer is checked against it, so of two deliveries of the same transfer at once one is
  accepted and the other answered `422`, because the order is paid already
  (`BankTransferOrderIT#aTransferReportedTwiceAtOnceIsAcceptedOnce`). Two orders of the same
  number placed at once are one order and one `409 Conflict`: the order is written before its
  workflow starts, and the second one fails on the order's number
  (`BankTransferOrderIT#anOrderPlacedTwiceAtOnceIsTakenOnce`).
- **Nothing of the order leaves the application.** The model decides nothing on data, so the
  aggregate is `@NoSyncWithBPMS` without a shared member.

## Run it

Start the engine as [the examples' README](../README.md#the-engine) says. The command there makes
the engine look for due timers every second, which the short timers of this example need. Then
start one of the twins:

```bash
cd examples/bank-transfer-order/springboot && mvn spring-boot:run
# or
cd examples/bank-transfer-order/quarkus && mvn quarkus:dev
```

Both answer on `http://localhost:8103`.

## Walk through it

The same steps, and those of the other ways listed below, are in
[`requests.http`](requests.http), to run one by one from the HTTP client of IntelliJ IDEA.

Place an order, and report its transfer as the bank would:

```bash
curl -s -X POST localhost:8103/orders -H 'Content-Type: application/json' \
  -d '{"orderId": "ORD-2001", "customerEmail": "jane.doe@example.com", "item": "hiking boots", "total": 89.90}'
curl -s -X POST localhost:8103/bank/transfers -H 'Content-Type: application/json' \
  -d '{"reference": "ORD-2001", "amount": 89.90}'
curl -s localhost:8103/orders/ORD-2001
```

```json
{"orderId":"ORD-2001","item":"hiking boots","total":89.90,"status":"SHIPPED","paymentInstructionsSent":true,"reminderSent":false,"trackingNumber":"PARCEL-100001","stockReleased":false}
```

This is `BankTransferOrderIT#anOrderPaidInTimeIsShippedWithoutAReminder`. The other ways an
order goes:

- report the transfer after more than ten seconds: the application logs the reminder mail, and
  the order is shipped all the same
  (`BankTransferOrderIT#aCustomerWhoPaysLateIsRemindedAndTheOrderShippedAllTheSame`);
- report nothing: after twenty-five seconds the order is `CANCELLED` with `"stockReleased": true`,
  and a transfer reported after that is refused with `409 Conflict`
  (`BankTransferOrderIT#anOrderNotPaidByTheDeadlineIsCancelledAndALateTransferRefused`);
- report a wrong amount, or a reference no order has: `422 Unprocessable Content`, and the order
  keeps waiting (`BankTransferOrderIT#aTransferWhichMatchesNoWaitingOrderIsLeftToAccounting`);
- place an order whose number is not 1 to 40 letters, digits and dashes, which lacks the e-mail
  address or the item, or whose total is not positive or has more than two decimals: `400 Bad
  Request`, and no order exists (`BankTransferOrderIT#anInvalidOrderIsRefused`).

In zenbpm-ui an order still waiting is an instance of `bank-transfer-order__bank_transfer_order`
with the order's number as its business key, its token at the receive task, and the path it took
once the transfer or the deadline came.
