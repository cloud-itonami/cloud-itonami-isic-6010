# Business Model: Community Radio Broadcasting Operations

## Classification
- Repository: `cloud-itonami-6010`
- ISIC Rev.5: `6010` — radio broadcasting
- Social impact: media plurality, local information access, content
  accountability

## Customer
- independent/community radio broadcasters needing an auditable
  license-compliance platform
- local-information and emergency-broadcast stations needing
  verifiable programming records
- regulators needing verifiable license-scope and programming records
- programs that cannot accept closed, unauditable broadcast-
  operations platforms

## Offer
- broadcast-license scope management
- robotics-assisted transmitter/studio-equipment inspection and
  maintenance
- program-schedule and transmission-dispatch records
- advertising billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per station/transmitter
- support retainer with SLA
- transmitter/studio-equipment inspection robot integration and
  maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (transmission outside verified license
  scope, programming that would violate a public-interest requirement)
  require human sign-off
- content cannot be transmitted outside its verified license scope
- billing records require verified evidence
- sensitive advertiser and audience data stays outside Git
