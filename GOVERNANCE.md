# Governance

`cloud-itonami-6010` is an OSS open-business blueprint for community
radio broadcasting operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Radio Broadcast Governor remains independent of the advisor.
- hard policy violations (out-of-license transmission, public-interest-programming violations, evidenceless billing record) cannot be overridden by human approval.
- every dispatch, sign-off, license and billing path is auditable.
- sensitive advertiser and audience data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or license-scope checks
- mishandling advertiser or audience data
- misrepresenting certification status
- failing to respond to safety incidents
