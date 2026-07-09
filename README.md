# cloud-itonami-6010

Open Business Blueprint for **ISIC Rev.5 6010**: radio broadcasting (a
licensed radio broadcast station operator).

This repository designs a forkable OSS business for community radio
broadcasting: broadcast-license scope management, robotics-assisted
transmitter/studio-equipment inspection and maintenance, and program-
schedule/advertising-billing records — run by a qualified operator so
a broadcaster keeps its own licensing and programming history instead
of renting a closed broadcast-operations platform.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (transmitter-tower
inspection, studio/transmission-equipment maintenance) operate under
an actor that proposes actions and an independent **Radio Broadcast
Governor** that gates them. The governor never transmits content
itself; `:high`/`:safety-critical` actions (any program transmission
outside the station's own verified license scope, any content that
would violate a public-interest programming requirement) require
human sign-off.

## Core Contract

```text
intake + identity + broadcast-license scope + program schedule
        |
        v
Broadcast Operations Advisor -> Radio Broadcast Governor -> license record, transmission dispatch, billing record, or human approval
        |
        v
robot actions (gated) + program/maintenance record + billing record + audit ledger
```

No automated advice can dispatch a transmission the governor refuses,
approve programming outside its verified license scope, or publish a
billing record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `6010`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) — shared telephony/audience-contact-records capability (call-in shows, listener contact lines)

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
