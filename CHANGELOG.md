# Changelog

## 3.0.0.29 (preview)

First preview of the 3.x line: a Java rewrite of the vCommunity management pack. Requires VCF Operations 8.18 or
later. See the [README](README.md) for known issues and the [install guide](docs/installing.md).

### Compared with the Python pack 0.8.1

**The same**
- Metric and property names, so history and anything built on them carries over.
- The dashboards, views, reports and alerts, converted. Nine views have new titles, and 19 views that only
  renamed a built-in view are replaced by the built-in view itself.
- The check-list files, in the same format.

**Changed**
- Content is installed under this pak's own ids. It no longer overwrites or deletes built-in views.
- The vCenter certificate is verified by default. Accept it in Validate Connection when you add the account.
  The Trust on First Use and Pinned modes for the vCenter connection are gone; Allow Insecure SSL turns checking off for labs.
- Unset values publish nothing. The Python pack published placeholders such as `None`, `null`, `N/A` or 0.
- The adapter reports its own health on the vCenter object (`vCommunity|Collection Health`). A failed read is
  counted there and never published as a value.
- Windows guest monitoring stops after two refused logons, to protect the account from lockout.

**Improved**
- Network Speed Degradation produces values for NICs with a fixed speed. Auto-negotiated NICs are not covered
  yet ([#39](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/39)).
- Memory figures account for the NVMe tier on hosts with memory tiering.
- Memory Tiering Latency is scored in microseconds.
- Host availability reads 100 % for healthy hosts on 8.18.
- DRS Score is left empty when DRS is off.
- The license expiry alert covers every license on a host.
- The nenic, nfnic and VIB Info views read the package data the adapter collects.
- VM Memory Limit charts the memory limit.
- The "Clusters not in Green zone" charts have their super metric.
- VMware Tools Sync Time and Upgrade Policy show real values.
- Account dropdowns preselect their defaults.
