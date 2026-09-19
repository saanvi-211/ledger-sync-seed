# Incident channel — INC-2026-09-11 (five lines)

1. **What broke:** account **4821 showed a ₹92,213.10 "WATER CAN" debit the user never made; the bank SMS behind it says ₹5.**
2. **How we found it:** `Amounts.first` matched the *stated balance* ("Avl Bal ₹92,213.10") when the real amount had no decimals ("Rs.5"), so the whole-rupee SMS poisoned the ledger row; reproduced on corpus-a, 29-07-26 window, and in SelfCheck before/after the fix.
3. **Who was affected:** the one reporting user on **4821 is the visible case; every corpus message whose amount has no decimals was at risk (55 + another multi-thousand spanning case), and the same guard is fixed once in `Amounts`.
4. **Why it cannot happen again:** the amount regex now makes decimals optional and matches the first *transaction* amount, never a balance, and the corpus is re-ran by `SelfCheck` (offline, no Gradle) plus AmountsTest regression tests the exact shapes from the incident.
5. **Follow-up / what it will not fix:** the ledger still cannot account for a genuine **₹7,500.00 unexplained debit on 4821 (29-07, 11:53→17:06)** — the corpus contains no message for it, so a reconciliation entry (not a made-up transaction) reports it honestly in `reconciliation.json`.
