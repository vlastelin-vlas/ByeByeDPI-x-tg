# DNS owner UID on Android 17

The feasibility gate in the selective DoT design passed on a Pixel 9 Pro XL running Android 17 (API 37), build `CP3A.261005.005`.

## Method

A temporary, separate `VpnService` advertised `10.10.10.10` as DNS and captured only the route to that address. A packet reader extracted IPv4/UDP source and destination tuples, then immediately called `ConnectivityManager.getConnectionOwnerUid(protocol, source, destination)`. Two temporary apps with different package names each called the standard `InetAddress.getByName` API with a unique uncached hostname. No DNS payloads were logged.

| Test app | Package UID | UID returned for its DNS packet | Transport |
| --- | ---: | ---: | --- |
| alpha | 10473 | 10473 | IPv4 UDP/53 |
| beta | 10474 | 10474 | IPv4 UDP/53 |

The two lookups were observed at 15:16:55 and 15:16:57 local device time. Other DNS packets in the same session had other UIDs, including 1021 and 10154; the API did not indiscriminately return the probe VPN's UID.

## Limits and cleanup

This proves attribution for Android's standard resolver over IPv4/UDP on this device. It does not yet prove TCP/53, IPv6, behavior with active strict Private DNS, or the future native tunnel bridge; those remain implementation tests. The test VPN dropped DNS responses, so the temporary session lasted only seconds.

Before and after testing: no active VPN network; Private DNS mode `opportunistic`, saved specifier `geohide.ru`. The Private DNS settings were never changed. The three temporary APKs were uninstalled after the test, and package listing confirmed their absence.
