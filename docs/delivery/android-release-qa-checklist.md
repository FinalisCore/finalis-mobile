# Android Release QA Checklist

## Release gate

- Use Java 17 for Gradle and Android builds.
- Build a `release` APK or App Bundle with a real HTTPS lightserver endpoint.
- Do not ship with mock lightserver mode enabled.
- Do not ship with an emulator-only endpoint such as `http://10.0.2.2:19444/rpc`.
- Verify release builds fail fast if `finalis.useMockLightserver=true` or `finalis.lightserverRpcUrl` is not HTTPS.
- Verify release signing configuration outside this repo before store/distribution builds.

## Build and environment checks

- Confirm `./gradlew :app:assembleRelease -Pfinalis.useMockLightserver=false -Pfinalis.lightserverRpcUrl=https://host/rpc` works in a Java 17 environment.
- Confirm cleartext HTTP is limited to the hosts in `app/src/main/res/xml/network_security_config.xml`.
- Confirm the active build-time `finalis.lightserverRpcUrl` is a real HTTPS endpoint.
- Confirm `finalis.useMockLightserver=false` for release artifacts.

## Manual QA

### First launch

- Launch on a clean install.
- Verify the app opens without crashes.
- Verify startup shows a loading state before wallet import.
- Verify diagnostics load and show the configured endpoint state.
- Verify release builds do not expose mock-only endpoint controls or emulator-only defaults.

### Wallet import

- Import a valid private key.
- Verify invalid private key input shows an error and does not crash.
- Verify a successfully imported wallet shows the expected address and public key.
- Restart the app and verify the imported wallet persists.

### Wallet reset

- Arm reset once and verify the warning appears.
- Confirm reset and verify wallet identity is removed.
- Verify submitted transactions are cleared locally.
- Verify the app returns to the import flow without stale balance/history state.

### Receive flow

- Open receive UI and verify the address matches the wallet identity section.
- Copy the address and verify clipboard output.
- Share the address and verify the share sheet opens correctly.
- Verify QR generation renders without crashes.

### Send preview

- Prepare a valid send.
- Verify preview shows amount, fee, total deduction, reserved-after-submit impact, and resulting available balance.
- Attempt to send more than spendable balance and verify the error references spendable balance, not total balance.
- Attempt invalid address and invalid amount input and verify clear errors.

### Send submit

- Broadcast a valid transaction against a usable endpoint.
- Verify the UI shows accepted submission status and does not imply settlement.
- Verify the transaction appears in submitted lifecycle state immediately after submit.
- Rapidly tap prepare/send controls and verify no duplicate send flow occurs.

### Submitted reservation behavior

- After submit, verify reserved balance increases.
- Verify available-to-send decreases accordingly.
- Verify the submitted transaction row explains the reservation effect and consumed input count.
- Attempt another send that would require reserved UTXOs and verify it is blocked by spendable-balance limits.

### Finalization detection

- Once the submitted tx finalizes on the lightserver, refresh or wait for polling.
- Verify the submitted tx leaves the submitted list.
- Verify it appears as recently finalized and then in finalized history.
- Verify reserved balance is released after finalization.

### Finalized history paging

- Verify the first finalized history page loads without regressions.
- If `Load more` is available, verify the next finalized page appends cleanly without duplicating prior entries.
- Verify the UI reports when the endpoint has no more finalized history pages.
- Verify paging errors stay local to history browsing and do not silently reset wallet state.

### Endpoint add/select/remove

- Add a valid HTTPS endpoint and verify it is saved and selectable.
- Add malformed endpoint input and verify a clear validation error.
- Select another valid endpoint and verify diagnostics update to the selected endpoint.
- Remove the active endpoint and verify another saved endpoint becomes active if available.
- Remove all saved runtime endpoints and verify the app falls back cleanly to the build default endpoint state.

### Endpoint mismatch handling

- Add or select an endpoint on the wrong network.
- Verify diagnostics clearly show mismatch, not unreachable.
- Verify sending is blocked while mismatch is active.
- Verify finalized reads are blocked until a valid endpoint is selected.

### Backend incompatibility handling

- Point the app at a stale or malformed backend missing finalized transition fields.
- Verify the app reports backend incompatibility explicitly, not generic sync lag or network mismatch.
- Verify address lookups stop when `validate_address` is incomplete or wrong-network.

### Endpoint failover

- Configure two valid endpoints where the first becomes unavailable.
- Verify the app fails over to the next valid endpoint.
- Verify diagnostics reflect the new active endpoint after failover.
- Verify wallet refresh/history still work after failover.

### Offline and unreachable behavior

- Disable network or use an unreachable endpoint.
- Verify diagnostics show unreachable state.
- Verify wallet refresh surfaces a recoverable error message.
- Verify the app does not crash and can recover once connectivity returns.

## Existing automated coverage

- Unit tests exist for crypto derivation, wallet session/storage logic, send service, reservation filtering, submitted transaction storage migration, endpoint failover, and wallet diagnostics state.

## Still requires real execution

- Full `:app:testDebugUnitTest` run in a Java 17 environment.
- `:app:assembleRelease` or App Bundle creation in a Java 17 environment.
- Release install smoke test on a physical Android device.
- Device/emulator manual QA across the checklist above.
- Real endpoint validation with production-like HTTPS lightservers.

## Known release blockers to clear before shipping

- Release signing configuration is not defined in this repo and must be supplied externally.
- A real HTTPS production lightserver URL must be provided for release builds.
- The current environment used during this audit could not execute Gradle tasks because Java 17 was unavailable.
