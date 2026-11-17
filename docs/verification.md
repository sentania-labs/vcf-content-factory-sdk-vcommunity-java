# What has been verified live

Tested on lab instances of VCF Operations 8.18.7, 9.0.2 and 9.1.1 (the 9.1.1 collector runs in FIPS mode),
on 2026-11-16.

| What | 8.18.7 | 9.0.2 | 9.1.1 |
|---|---|---|---|
| Collects cleanly, all collectors healthy | Yes | Yes | Yes |
| Fresh install following the install guide | | | Yes |
| Certificate accept dialog, then verified collection | Yes | Already trusted, no dialog needed | Yes |
| Account stops safely until the certificate is accepted | Yes | | |
| Wrong vCenter name is rejected | Yes | Yes | Yes |
| Allow Insecure SSL on and off | Yes | Yes | Yes |
| Account dropdowns preselect their defaults | | | Yes |
| Uninstall leaves built-in views and policies untouched | | | Yes |
| Moving from the Python pack, including restoring built-in views | | | Yes |
| Metric history continues after moving from the Python pack | Yes | | |
| Host availability reads 100 for healthy hosts | Yes | | |
| Memory figures correct on hosts with memory tiering | | | Yes |
| Network Speed Degradation produces values | | Yes | |
| License alert condition matches real license names (shown with a test symptom) | | Yes | |
| "Clusters not Green" super metric counts correctly | | Yes | |
| Cluster Performance 2.0 dashboard fills after picking a cluster | | | Yes |

A blank cell means not tested on that version, not that it failed.

## Not yet verified

- The values Windows guest monitoring publishes. One run collected from two member servers and was refused by
  two domain controllers.
- The nenic, nfnic and VIB Info views with data. No lab host has those packages.
- An expired vCenter certificate on a live instance.

The detailed record of each test is in the [detailed install reference](installing-reference.md) and the
[design document](design/vcommunity-java-port-design.md).
