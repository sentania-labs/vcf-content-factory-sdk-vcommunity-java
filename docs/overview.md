# Overview

## What it does

The pak connects to a vCenter and adds data that the built-in vCenter adapter does not collect. The data lands on
the objects you already have in VCF Operations, under a `vCommunity` group, so it shows up next to the built-in
metrics and works in your own dashboards, views and alerts.

| On | It adds |
|---|---|
| Clusters | HA, DRS and EVC settings; performance, risk and consumption scores; availability; a periodic health check |
| Hosts | Hardware, BIOS, storage adapter and NIC details; licensing; installed packages; advanced settings; a performance score; availability |
| VMs | Configuration and advanced parameters; VMware Tools settings; a performance score; optional Windows services and events |
| Datastores | Configuration details |
| vCenter | Settings, certificate tracking, and the pak's own collection health |
| Datacenters and the vSphere World | Roll-ups of the above |

It also creates three kinds of object of its own: **Content Library**, **Library Item** and **VM Template**.

## What ships with it

| Content | Count |
|---|---:|
| Dashboards | 52 |
| Views | 170 |
| Reports | 16 |
| Alerts | 4 |
| Super metrics | 3 |

Most dashboards, and all alerts and super metrics, are off until you enable them. See the
[install guide](installing.md#after-installing).

## How it works

- **It reads from vCenter and writes into VCF Operations.** Each five-minute cycle it logs in to the vCenter,
  reads configuration, and attaches the values to the matching objects of the built-in vCenter adapter.
- **Scores are calculated from metrics VCF Operations already has.** The performance, risk and consumption
  scores use the built-in adapter's metrics. If one of those metrics is switched off in your policy, the part of
  the score that needs it is left out.
- **Some data is opt-in.** Advanced settings, packages and VM options are only collected for the items you list
  in a [check list](installing.md#check-lists).
- **It tells you when something is wrong.** The vCenter object carries `vCommunity|Collection Health` keys that
  show each collector's status, what failed and why.

## What to expect

- **Missing means missing.** If vCenter has no value for a setting, nothing is published. You will not see
  placeholder text or a zero standing in for "unknown".
- **A failed read is never shown as a value.** It is counted in Collection Health instead.
- **License keys are masked.** Only the last group of a license key is stored.
- **Passwords and tokens are never logged.**
- **New objects take a cycle or two** to get their values after the built-in adapter discovers them.

## More

- [Install guide](installing.md)
- [Known issues](../README.md#known-issues)
- [Metrics reference](../REFERENCE.md)
- [Technical notes](technical-notes.md): the detailed behaviour, for the curious
