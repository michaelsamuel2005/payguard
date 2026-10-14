# 5. Warn and hold thresholds are chosen by a rule written before the results

Status: accepted, 14 October 2026

## Context

Each payment gets a risk score: the sum of the weights of its signals (`Signal`). Two thresholds turn the score
into an outcome: at or above `warnAt` the customer sees a specific warning and must acknowledge it; at or above
`holdAt` the payment waits for a person in the review team.

Both thresholds trade scams stopped against genuine customers interrupted. Picking them by eye, or after looking at
the results, would let the numbers argue for whatever looked best. So the rule comes first and the code applies it.

## Decision

Interrupting genuine customers has a cost, so the rule caps it, then stops as many scams as it can within the cap:

1. **Hold at most 1 in 100 genuine payments.** A held payment waits for a person; more than that and the queue,
   and the customers in it, suffer.
2. **Warn or hold at most 1 in 10 genuine payments.** Warnings shown too often get clicked through.
3. Among the threshold pairs that meet both caps, choose the one that **holds the most scams**; if tied, the one
   that **warns or holds the most scams**; if still tied, the one that **interrupts the fewest genuine payments**;
   if still tied, the **lowest thresholds**.

The fail-safe rule is not part of the search: a payment whose name could not be checked is warned at least,
whatever the thresholds.

`ScamPolicyEvaluationTest` applies the rule to simulated payments with planted scams (seeded, so every run is
identical), writes `docs/SCORECARD.md`, and fails if `ScamPolicy.DEFAULT` differs from the rule's choice or if
the committed scorecard differs from what the code now produces.

## Consequences

- The thresholds are only as good as the simulation. They are a starting point: real thresholds need real
  payments labelled as scams or not, and the same rule applied to them.
- The scorecard says which planted scams get through at the chosen thresholds, so the limits are stated, not found
  later.
- Changing a weight, a signal or the simulation changes the scorecard, and the test makes that change visible in
  review.
