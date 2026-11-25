# Wi-Fi observation and network model

## 1. Do not collapse different identities

HomeRadar must model these as separate concepts:

- **SSID:** human-readable network name. It is not a unique identifier.
- **BSSID:** MAC-like identifier broadcast by a particular basic service set/radio interface. It may be randomized, replaced, hidden, or unavailable.
- **Radio:** a radio interface/band/channel context exposed by the platform.
- **Access point:** a physical or logical device that may expose multiple radios/BSSIDs.
- **Network group:** a user-visible grouping of related observations. Membership may be confirmed, user-defined, or inferred.
- **Mesh/roaming relationship:** a relationship between APs, which may be observed through an integration or inferred with a confidence score.

One SSID can be advertised by many BSSIDs. One physical AP can advertise several BSSIDs. Similar names, vendor OUIs, channels, or signal levels are not proof of shared hardware or mesh membership.

## 2. Raw observation

A raw observation should preserve, when Android exposes them:
- SSID (including hidden/unknown state);
- BSSID (including unavailable/redacted state);
- RSSI in dBm;
- frequency in MHz;
- channel derived from frequency only when mapping is unambiguous;
- capabilities/security summary;
- Wi-Fi standard information if available;
- scan result timestamp/age;
- time the app received the result;
- source and API level;
- freshness and permission/availability flags.

Do not replace unknown values with invented defaults. Keep raw platform values and normalized fields separately when normalization could lose information.

## 3. Freshness and throttling

Wi-Fi scans are not a continuous sensor. Android may throttle active scans and may return cached results. The app must:
- preserve the platform scan timestamp where available;
- record receive time separately;
- calculate age;
- label stale/cached results;
- never assign a new timestamp merely because an old result was received again;
- associate the measurement with the pose at the measurement time, not the UI update time;
- expose scan gaps in the session.

Motion and camera tracking may run at a higher rate. Pose interpolation is allowed only within a configured time gap and must propagate uncertainty.

## 4. Network grouping

Represent group membership with:
- relation type: same SSID, candidate same AP, user-confirmed same AP, candidate mesh peer, router-confirmed mesh peer, or unknown;
- confidence score;
- evidence list;
- first/last observation times;
- user override state.

Keep SSID grouping distinct from AP and mesh grouping. Never merge BSSIDs solely because their SSID matches.

Potential evidence includes:
- repeated co-observation and spatial signal patterns;
- stable vendor/OUI hints (weak evidence; not identity proof);
- band/radio behavior;
- user confirmation;
- explicit router/controller topology data (stronger evidence, with source and timestamp).

## 5. Roaming and mesh

A phone may roam between BSSIDs while the SSID remains the same. HomeRadar should record the transition and its timing, not assume that every same-SSID BSSID belongs to one mesh.

Mesh backhaul topology is generally not fully discoverable from ordinary client-side scan results. Passive scans can support hypotheses about coverage and roaming, but not guarantee a complete topology.

Optional router integrations should be adapters with explicit permissions, local network access, vendor/API documentation, and a clear indication of whether a relation is directly reported or inferred.

## 6. Bands and channels

Keep frequency in MHz as the canonical observed value. Derive band/channel labels only when valid. Support 2.4 GHz, 5 GHz, and 6 GHz where the phone, regulatory domain, chipset, and Android version expose them. Do not assume every device can scan every band.

## 7. Heatmap inputs

A heatmap cell should be derived from a set of observations, not a single magic color. Preserve:
- sample count;
- aggregation method;
- minimum/maximum/median or robust summary;
- spatial radius/cell size;
- time window/session;
- confidence/uncertainty;
- whether the value is measured or interpolated.

Do not extrapolate across large unsampled areas without labeling the estimate. Display sample density so users can distinguish a well-sampled route from a sparse one.
