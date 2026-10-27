# Selective DoT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route ordinary DNS requests from a separate set of VPN apps to `geohide.ru:853` over TLS while preserving ByeDPI and existing DNS behavior for other apps.

**Architecture:** Keep one Android VPN and the existing `hev-socks5-tunnel`. First prove that the VPN can identify the originating app for DNS flows on the connected Android 17 device. If that proof succeeds, divert port-53 flows inside the tunnel, choose by UID, use a protected DoT connection for selected UIDs, and pass all other flows through their existing path.

**Tech Stack:** Kotlin, Android VpnService/ConnectivityManager, Android NDK C/JNI, lwIP, JUnit 4, Android instrumentation, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-22-selective-dot-design.md`

## Global Constraints

- The target device is a Pixel 9 Pro XL running Android 17 (API 37), connected through ADB as `4B211FDAS0058K` on 22 October 2026. Recheck its identity before installation.
- The project compiles with SDK 36, targets SDK 34, and has min SDK 21. Guard `ConnectivityManager.getConnectionOwnerUid` at API 29; never call it on older Android.
- DoT is off by default. When off, the existing TUN, DNS, app filter, and proxy behavior must stay unchanged.
- The DoT server is fixed at `geohide.ru:853`; verify the TLS certificate against `geohide.ru` and never silently fall back to plaintext for selected apps.
- Only traffic already admitted into the VPN can use this feature. System Private DNS, application DoH/DoQ, and VPN-excluded apps are outside its reach.
- Do not read or print `local.properties`: it contains signing secrets. Do not alter the user's untracked `build-windows.ps1`.
- **Hard stop:** if the Android 17 probe cannot identify the actual app for system-resolver DNS from two different apps, do not implement or expose selective DoT. Report the evidence and revise the design with the user.

## Review Focus

1. DNS from Android's system resolver is attributed to the app, rather than a system UID or `INVALID_UID`; Task 1 records both UIDs and fails the gate otherwise.
2. A selected app excluded from the VPN is shown as ineligible rather than silently appearing protected; Task 2 tests blacklist and whitelist conflicts.
3. A bad certificate, timeout, or server failure never sends a selected query to the legacy DNS; Task 4 tests all three outcomes.
4. Unknown UID and changed/uninstalled package selections never inherit a selected app's policy; Tasks 2 and 5 test this.
5. TCP DNS frames split across reads, IPv6 DNS, and malformed lengths cannot corrupt or stall the TUN loop; Task 5 tests each case.

---

## File map

- `app/src/main/java/io/github/romanvht/byedpi/services/ByeDpiVpnService.kt`: VPN setup, Android owner-UID lookup, DoT lifetime, route decision.
- `app/src/main/java/io/github/romanvht/byedpi/services/NativeSession.kt`, `NativeEngine.kt`, `EngineProtocol.kt`, `data/Configuration.kt`: preference-to-service configuration across the existing IPC boundary.
- `app/src/main/java/io/github/romanvht/byedpi/services/SelectiveDns.kt` (new): policy and flow decision; no Android UI dependencies.
- `app/src/main/java/io/github/romanvht/byedpi/services/DotClient.kt` (new): protected TLS transport and RFC 7858 framing.
- `app/src/main/java/io/github/romanvht/byedpi/core/TProxyService.kt`, `app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c`, `hev-socks5-tunnel.c`, `hev-socks5-tunnel/src/core/src/hev-socks5-session-udp.c` and TCP peer: native-to-service bridge and port-53 diversion. Inspect actual TCP peer filename before editing.
- `app/src/main/java/io/github/romanvht/byedpi/fragments/MainSettingsFragment.kt`, `AppSelectionFragment.kt`, `adapters/AppSelectionAdapter.kt`, `app/src/main/res/xml/main_settings.xml`, `res/values*/strings.xml`: settings and a separate app list.
- `app/src/main/java/io/github/romanvht/byedpi/utility/SettingsUtils.kt`, `DiagnosticUtils.kt`: import/export and diagnostics.

## Task 1: Prove app attribution on Android 17

**Files:** Modify `ByeDpiVpnService.kt`, `TProxyService.kt`, `hev-jni.c`, `hev-socks5-tunnel.c`; create `app/src/androidTest/java/io/github/romanvht/byedpi/DnsOwnerProbeTest.kt`. If the two installed browsers cannot produce ordinary port-53 queries, create two temporary helper APKs under `tools/dns-probe/` with distinct `applicationId` values and the activity shown below. The production hook in Task 5 replaces the probe.

**Interfaces:** Produce `DnsFlow(protocol: Int, source: InetSocketAddress, destination: InetSocketAddress)` and `ownerUid(flow): Int`. No later task starts until the observed UID equals each test app's UID for the system resolver.

- [ ] **Step 1: Write the probe expectation.** In `DnsOwnerProbeTest.kt`, record `packageManager.getPackageUid(packageName, 0)` for `com.android.chrome` and `com.yandex.browser` (both present on the phone) and compare those values with logged DNS flow owners. Check `adb shell settings get global private_dns_mode`; if Private DNS is active, have the user temporarily turn it off before this test. Launch each browser on a unique uncached HTTP hostname to induce a system lookup. If either browser does not produce port-53 traffic, use two helper APKs that each run the same `InetAddress.getByName` call under a different app UID. Use a bounded 10-second observation window per app.

```kotlin
data class DnsFlow(val protocol: Int, val source: InetSocketAddress, val destination: InetSocketAddress)
fun ConnectivityManager.ownerUid(flow: DnsFlow): Int =
    if (Build.VERSION.SDK_INT >= 29) getConnectionOwnerUid(flow.protocol, flow.source, flow.destination)
    else Process.INVALID_UID

// Activity.onCreate in each helper APK, with distinct applicationId values:
Thread { InetAddress.getByName("probe-" + System.nanoTime() + ".example.com") }.start()
```

- [ ] **Step 2: Run the baseline build and probe test.** Run `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:installDebug :app:installDebugAndroidTest` with the verified phone as the only connected device. Then run `adb -s 4B211FDAS0058K shell am instrument -w io.github.ewenloy.byedpixtg.test/androidx.test.runner.AndroidJUnitRunner`. Expected: probe first fails because no native DNS tuple callback exists.
- [ ] **Step 3: Add a debug-only tuple callback at `hev_tunnel_read` before `netif.input`.** Parse IP version, transport protocol, source/destination address and port with header-length and fragment checks; call a JNI observer without copying DNS payload. Look up and log only the observed UID and expected app UID. No persistent settings or traffic routing in this step.

```c
/* Only complete, unfragmented UDP/TCP packets with destination port 53. */
if (debug_dns_probe && parse_dns_flow(buf, &flow))
    hev_jni_report_dns_flow(&flow);
```

- [ ] **Step 4: Rerun on the phone.** For each app, record package name, expected UID, observed UID, Android DNS mode, protocol, source/destination tuple, and whether the query came through the system resolver. Pass only if two distinct system-resolver app UIDs match. If the platform reports a system/invalid UID, remove probe-only code, commit the observations to a report in `docs/superpowers/`, and stop the plan before Task 2. Remove temporary helper APK sources after saving the measurements; do not ship probe apps.
- [ ] **Step 5: Commit passing probe code and results.** `git add` only the probe files and report; `git commit -m "test: prove DNS app attribution on Android 17"`.

## Task 2: Separate DNS selection and eligibility policy

**Files:** Create `services/SelectiveDns.kt`, `app/src/test/java/io/github/romanvht/byedpi/services/SelectiveDnsTest.kt`; modify `AppSelectionFragment.kt`, `AppSelectionAdapter.kt`, `MainSettingsFragment.kt`, `main_settings.xml`, `res/values/strings.xml`, `res/values-en/strings.xml`, `SettingsUtils.kt`. Add concise labels in other existing locales or let Android use the Russian default after checking localization policy.

**Interfaces:** `SelectiveDns.eligiblePackages(vpnMode: String, vpnPackages: Set<String>, dnsPackages: Set<String>): Set<String>` and `SelectiveDns.uidForPackages(pm: PackageManager, packages: Set<String>): Set<Int>`. Use preference keys `dot_enabled` (Boolean) and `dot_selected_apps` (StringSet).

- [ ] **Step 1: Write failing JUnit policy tests.** Cover disabled VPN filter, whitelist inclusion, blacklist exclusion, removed packages, empty DNS selection, and UID collision between packages. In each case assert only eligible UIDs are returned; package lookup failures are ignored and diagnosed.

```kotlin
assertEquals(setOf("a"), SelectiveDns.eligiblePackages("whitelist", setOf("a", "b"), setOf("a", "c")))
assertEquals(emptySet<String>(), SelectiveDns.eligiblePackages("blacklist", setOf("a"), setOf("a")))
```

- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest --tests '*SelectiveDnsTest'`.** Expected: fails before `SelectiveDns` exists.
- [ ] **Step 3: Implement the minimal policy.** Parameterize the existing app picker with `arguments?.getString("selection_key") ?: "selected_apps"` and pass the key into its adapter. Add a `dot_selected_apps` preference with `app:fragment` and fragment arguments, a `dot_enabled` switch, and a summary for VPN-filter conflicts. Keep `dot_selected_apps` in the SETTINGS export/import section; do not add it to `separatePreferenceKeys`.

```kotlin
fun eligiblePackages(mode: String, vpn: Set<String>, dns: Set<String>): Set<String> = when (mode) {
    "whitelist" -> dns intersect vpn
    "blacklist" -> dns - vpn
    else -> dns
}
```

- [ ] **Step 4: Rerun policy tests and inspect export/import.** Verify the new StringSet survives SETTINGS export/import and is removed by reset. Verify VPN `selected_apps` remains independent. Commit: `git commit -m "feat: add separate DoT app selection"`.

## Task 3: Carry DoT configuration through the existing engine

**Files:** Modify `data/Configuration.kt`, `services/NativeEngine.kt`, `EngineProtocol.kt`, `NativeSession.kt`, `ByeDpiVpnService.kt`; create `app/src/androidTest/java/io/github/romanvht/byedpi/services/DoTConfigurationTest.kt` (Android `Bundle` requires an instrumented test with this project's current dependencies).

**Interfaces:** Add `dotEnabled: Boolean = false` and `dotPackages: List<String> = emptyList()` to `Configuration`; add `DOT_ENABLED` and `DOT_PACKAGES` bundle keys. `ByeDpiVpnService` resolves eligible package UIDs when the session starts, not on every DNS packet.

- [ ] **Step 1: Write a failing round-trip test.** Build a `Configuration` with DoT enabled and two packages; serialize it into the engine `Bundle`, then deserialize and compare. Add a default test proving old Bundles decode as DoT disabled with no packages.

```kotlin
assertFalse(decodeConfiguration(Bundle()).dotEnabled)
assertEquals(emptyList<String>(), decodeConfiguration(Bundle()).dotPackages)
```

- [ ] **Step 2: Run `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.romanvht.byedpi.services.DoTConfigurationTest`.** Expected: fails on missing fields/keys.
- [ ] **Step 3: Add the two fields and bundle writes/reads beside existing `DNS`, `IPV6`, and `APPS`.** Read `dot_enabled` and `dot_selected_apps` in `NativeEngine.configuration`. Keep proxy mode unchanged. Reuse the exact same keys on both sides of the IPC boundary.

```kotlin
putBoolean(EngineProtocol.DOT_ENABLED, configuration.dotEnabled)
putStringArray(EngineProtocol.DOT_PACKAGES, configuration.dotPackages.toTypedArray())
```

- [ ] **Step 4: Run the focused test and a debug build.** Expected: both pass, and with `dot_enabled=false` the existing tunnel config and VPN DNS address are byte-for-byte unchanged. Commit: `git commit -m "feat: pass DoT settings to VPN session"`.

## Task 4: Protected, verified DNS over TLS client

**Files:** Create `services/DotClient.kt`, `app/src/test/java/io/github/romanvht/byedpi/services/DotClientTest.kt` and an instrumentation test for Android socket protection.

**Interfaces:** `DotClient.query(request: ByteArray): ByteArray` runs off the main and native tunnel threads; constructor receives a function that creates a protected, externally bound socket. A caller supplies only complete DNS messages (without the two-byte TCP length). No plaintext fallback.

- [ ] **Step 1: Write failing tests using a local TLS test server.** Assert the request is prefixed by its unsigned 16-bit length, a reply split across several reads is assembled, a zero or >65535-byte message is rejected, a mismatched certificate fails, a server that accepts but never replies times out, and no alternate DNS transport is invoked.

```kotlin
val responseLength = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
assertArrayEquals(expected, input.readNBytes(responseLength))
```

- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest --tests '*DotClientTest'`.** Expected: fails before `DotClient` exists.
- [ ] **Step 3: Implement `DotClient`.** Resolve the server on an underlying network, protect and bind the raw socket before connecting, wrap it with the platform TLS socket factory using peer host `geohide.ru`, enable HTTPS hostname verification, set connect/read timeouts, and implement RFC 7858 length framing. Reject failed `protect`, bootstrap, TLS handshake, verification, and read without retrying through legacy DNS.

```kotlin
val tls = sslSocketFactory.createSocket(protectedSocket, "geohide.ru", 853, true) as SSLSocket
tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
tls.startHandshake()
DataOutputStream(tls.outputStream).apply { writeShort(request.size); write(request); flush() }
```

- [ ] **Step 4: Rerun tests and an on-device connectivity check.** Confirm that the TLS peer name is `geohide.ru`, the socket does not re-enter the VPN, and an unreachable server produces a bounded failure. Commit: `git commit -m "feat: add verified DoT transport"`.

## Task 5: Divert DNS flows without changing other traffic

**Files:** Modify `hev-socks5-tunnel.c`, the UDP/TCP session files under `hev-socks5-tunnel/src/core/src/`, `hev-jni.c`, `core/TProxyService.kt`, `services/ByeDpiVpnService.kt`, `services/NativeSession.kt`; create `app/src/androidTest/java/io/github/romanvht/byedpi/SelectiveDnsTunnelTest.kt`.

**Interfaces:** The native bridge reports `(flow, requestBytes, requestId)` to `ByeDpiVpnService`; the service returns `(requestId, responseBytes)` asynchronously. Native session state owns the original lwIP UDP/TCP endpoint and sends exactly one reply or DNS SERVFAIL. Policy chooses DoT only when `ownerUid(flow)` is in the eligible UID set.

- [ ] **Step 1: Write failing tunnel tests.** On the device, send UDP and TCP DNS from a selected app and an unselected app; assert selected DoT traffic, legacy unselected traffic, identical ByeDPI routing of non-DNS packets, and no plaintext packet for a selected flow after TLS failure. Add tests for unknown UID, fragmented TCP frames, IPv6, malformed lengths, and VPN stop during an outstanding query.

```kotlin
assertEquals(DnsRoute.DOT, route(uid = selectedUid, port = 53))
assertEquals(DnsRoute.LEGACY, route(uid = unselectedUid, port = 53))
assertEquals(DnsRoute.LEGACY, route(uid = Process.INVALID_UID, port = 53))
```

- [ ] **Step 2: Run `.\gradlew.bat :app:connectedDebugAndroidTest`.** Expected: routing tests fail before interception exists.
- [ ] **Step 3: Replace the probe hook with the production diversion at the lwIP UDP/TCP session boundary.** Match destination port 53 and only valid complete DNS messages; keep endpoint and original destination. Post JNI events to a bounded Kotlin executor, never block the tunnel thread waiting for TLS, and return responses on the tunnel's event/task thread. Pass packets with no matching DNS rule to existing SOCKS5 code. Use a bounded pending-query map keyed by request ID, clear it on stop, and synthesize SERVFAIL for selected-query failures.

```kotlin
enum class DnsRoute { DOT, LEGACY }
fun route(uid: Int, port: Int): DnsRoute =
    if (port == 53 && uid in selectedUids) DnsRoute.DOT else DnsRoute.LEGACY
```

- [ ] **Step 4: Run tunnel tests and a debug build on all configured ABIs.** Verify normal ByeDPI operation with DoT off, unselected DNS unchanged, selected DNS encrypted, TCP and UDP responses correct, and no crash on VPN restart. Commit: `git commit -m "feat: route selected VPN DNS through DoT"`.

## Task 6: User state, diagnostics, and final verification

**Files:** Modify `MainSettingsFragment.kt`, `utility/DiagnosticUtils.kt`, `res/values/strings.xml`, `res/values-en/strings.xml`, relevant translated `strings.xml` files, and `README.md`; add/update focused tests for summaries and state transitions.

**Interfaces:** Show `off`, `active`, `unavailable because app is outside VPN`, `blocked by system Private DNS`, and `TLS error` states. Do not log DNS names or request payloads by default.

- [ ] **Step 1: Write failing tests for status mapping.** Assert each state has a distinct user-visible string, that enabling the switch with no selected apps gives an actionable empty-list summary, and that selected apps outside the VPN are counted but not claimed active.
- [ ] **Step 2: Run `.\gradlew.bat :app:testDebugUnitTest`.** Expected: new state tests fail.
- [ ] **Step 3: Add status/diagnostics and short documentation.** Show the active DoT server, selected/eligible app counts, the system Private DNS/DoH limits, and the bootstrap hostname lookup disclosure. Wire real TLS and unknown-UID failures into diagnostics. Keep copy accurate for Android versions below API 29 by showing the feature unavailable.

```kotlin
val status = if (Build.VERSION.SDK_INT < 29) DotStatus.UNSUPPORTED
    else if (!dotEnabled) DotStatus.OFF
    else if (eligibleUids.isEmpty()) DotStatus.NO_ELIGIBLE_APPS
    else DotStatus.ACTIVE
```

- [ ] **Step 4: Verify on the connected device.** Run `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest`; manually verify VPN on/off, selected and unselected apps, app-filter conflicts, system Private DNS, bad TLS, export/import, and proxy mode. Inspect `git diff --check` and the final diff. Commit: `git commit -m "docs: explain selective DoT state and limits"`.

## Stop condition

Task 1 is a hard feasibility gate. A failing owner-UID probe means this plan ends with a measured report and no user-facing DoT switch. If it passes, finish Tasks 2–6 in order, with focused tests and review at each task boundary. A final whole-branch review checks both the Android and native paths before claiming completion.
