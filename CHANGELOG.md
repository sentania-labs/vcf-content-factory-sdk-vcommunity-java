# Changelog

## 3.0.0.29 (preview)

First preview of the 3.x line: a Java rewrite of the vCommunity management pack. Requires VCF Operations 8.18 or
later. See the [README](README.md) for known issues and the [install guide](docs/installing.md).

### Compared with the Python pack 0.8.1

**Unchanged**
- Metric and property names, so history and anything built on them carries over.
- The dashboards, views, reports, alerts and check-list files, converted.

**Changed**
- Content is installed under this pak's own ids. It no longer overwrites or deletes built-in views.
- The vCenter certificate is verified by default. Accept it in Validate Connection when you add the account.
  The old Trust on First Use and Pinned modes are gone; Allow Insecure SSL turns checking off for labs.
- Unset values publish nothing. The Python pack published placeholders such as `None`, `null`, `N/A` or 0.
- The adapter reports its own health on the vCenter object (`vCommunity|Collection Health`). A failed read is
  counted there and never published as a value.
- Windows guest monitoring stops after two refused logons, to protect the account from lockout.

**Fixed**
- Network Speed Degradation now produces values (needs a policy setting; see the install guide).
- Memory consumed no longer reads over 100 % on hosts with memory tiering.
- Memory Tiering Latency is scored in the right unit.
- Host availability no longer reads 75 % for healthy hosts on 8.18.
- DRS Score no longer reads 0 when DRS is off.
- The license expiry alert covers every license, not one hard-coded license name.
- The nenic, nfnic and VIB Info views read keys that exist.
- VM Memory Limit charts the memory limit, not the CPU limit.
- "Clusters not in Green zone" charts have a super metric behind them.
- VMware Tools Sync Time and Upgrade Policy show real values.
- Account dropdowns preselect their defaults.

Design decisions behind each change are in the [design document](docs/design/vcommunity-java-port-design.md).
