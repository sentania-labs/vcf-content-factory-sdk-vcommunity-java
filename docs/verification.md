# What has been verified live

Lab instances: VCF Operations 8.18.7, 9.0.2 and 9.1.1 (FIPS collector), 2026-11-16.


- **Build 0.0.0.29, 9.1.1 only** (a fresh install that followed `docs/installing.md`, on a FIPS collector): the dropdown
  defaults preselected on the Add Account form; the certificate review dialog and accept in Validate Connection, then verified
  collection (four clean cycles, nothing withheld); Allow Insecure SSL on and back off; an invalid setting reported once and
  shown in `Configuration Warnings`; uninstall and reinstall; the "vSphere Cluster Performance 2.0" dashboard filling after a
  cluster is picked (click the row, then "Internal interaction"); 412 series per steady cycle, as on 0.0.0.26.
- **Build 0.0.0.27, 8.18.7 and 9.0.2** (upgraded accounts): verified by default. On 8.18.7 the upgraded account failed closed
  and the accept dialog fixed it; on 9.0.2 the lab CA was already trusted, so there was no dialog. Build 29 has not been
  installed on 8.18.7 or 9.0.2.
- **Earlier builds:** Network Speed Degradation (9.0.2, build 22); the memory tiering divisor and latency unit (9.1.1);
  the migration from a Python pack version, including restoring the built-in views (9.1.1); build 0.0.0.26 over earlier
  builds on all three versions, collecting with every collector OK.
- **Not verified:** the values the guest checks publish (one live run on 9.0.2, below); the five package views with data (no lab
  host runs those packages); the first-sight certificate status `Recorded`; an expired certificate on a live instance.

