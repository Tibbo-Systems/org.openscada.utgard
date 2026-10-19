# OPC DA over DCOM — `Complex ping failed, hresult: 5`

## Symptom

j-interop logs under logger `org.jinterop`, every few minutes:

```
org.jinterop.dcom.core.PingObject read
SEVERE: Some error ! Complex ping failed , hresult: 5
```

Source: `JIComOxidStub.java` (`PingObject.read`). j-interop's keepalive sends
`IObjectExporter::ComplexPing` to the server's DCOM OXID resolver (TCP 135) to renew the
lease on the references it holds. The response HRESULT is `5` = Win32 `ERROR_ACCESS_DENIED`:
the hardened Windows server refuses the ping because it arrives with weak / anonymous
authentication.

## Impact

**None in practice.** The subscription survives with *every* ping failing — the continuous
`OnDataChange` callback traffic keeps the references alive, so the failed resolver ping never
drops the session. It is log noise, not a functional fault.

**One caveat:** the ping only matters as a keepalive during *inactivity*. If an active
subscription ever goes fully idle (no callbacks) for longer than the server's ping timeout
(~6 min), failing pings could then let the server GC the references and drop the session.
With a live subscription delivering updates, that will not happen.

## Server-side policy options (on the Windows / Matrikon machine)

Each of these *relaxes a DCOM/RPC hardening knob* so the resolver accepts the ping. Most
probable cause first.

### 1. "Restrict Unauthenticated RPC clients" (usual culprit)

- GPO: Computer Configuration → Administrative Templates → System → Remote Procedure Call →
  **Restrict Unauthenticated RPC clients** → set to **None**.
- Registry equivalent: `HKLM\SOFTWARE\Policies\Microsoft\Windows NT\RPC` →
  `RestrictRemoteClients = 0` (DWORD).
- Restart the server (or the RPCSS service). This lets the OXID resolver accept the ping's
  anonymous RPC call.

### 2. DCOM COM Security — remote access for the identity

- `dcomcnfg` → Component Services → Computers → My Computer → Properties → **COM Security** →
  *Access Permissions* → **Edit Limits** → ensure the connecting user (and, if present,
  `ANONYMOUS LOGON`) has **Remote Access = Allow**.

### 3. DCOM activation hardening (post-2023, CVE-2021-26414)

- `HKLM\SOFTWARE\Microsoft\Ole\AppCompat` → `RequireIntegrityActivationAuthenticationLevel`.
  Enforces packet-integrity auth on DCOM. Usually surfaces as `0x80070005` on *activation*
  rather than `5` on the ping, so it is the least likely of the three here — but it is the
  other hardening lever in this area.

### How to confirm which one

Flip #1 to None, restart, and watch whether `Complex ping failed` disappears. If it persists,
check the #2 access limits.

## Recommendation

**Don't.** All three weaken the server's security posture (allowing less-authenticated
RPC/DCOM) purely to silence a log line with zero functional impact — data flows fine with
every ping failing. If the noise ever genuinely matters, lowering that one `SEVERE` log level
in the patched j-interop is the cheaper, safer trade than loosening the Windows server.

The more correct engineering fix is client-side: make j-interop send the ping under the same
authenticated (NTLM) context as the main connection, rather than at a weaker level. See below.

## Client-side fix (applied)

The ping is **already sent as the authenticated session user** — `JIComOxidStub` is built with
`session`'s domain/username/password (`JIComOxidRuntime.java:266`, credentials from `:369-371`).
It is **not** anonymous. What it lacked was *authentication level*: `JIComOxidStub`'s static
`defaults` forced the ping's NTLM context to connect-level only (`sign=false`, `seal=false`,
`keyExchange=false`), while the main DCOM connection runs with signing + sealing. Post-2023 DCOM
hardening (CVE-2021-26414) requires at least packet integrity (`RPC_C_AUTHN_LEVEL_PKT_INTEGRITY`),
so the connect-level resolver ping is rejected with `ACCESS_DENIED (5)`.

**Fix:** raise the ping's auth level to match the main connection, in
`j-interop/src/org/jinterop/dcom/core/JIComOxidStub.java` static `defaults`:

```java
defaults.put("rpc.ntlm.sign","true");        // packet integrity (signing)
defaults.put("rpc.ntlm.seal","true");        // packet privacy (sealing)
defaults.put("rpc.ntlm.keyExchange","true"); // REQUIRED to derive the NTLM session key for sign/seal
```

Notes:
- `keyExchange` **must** be `true` — signing/sealing need the negotiated NTLM session key
  (`NTLMSSP_NEGOTIATE_KEY_EXCH`).
- NTLMv2 is already propagated via `rpc.ntlm.ntlmv2` (from `holder.useNTLMv2`), so this stays NTLMv2.
- If signing/sealing can't be negotiated with the server, the ping will fail at a different point
  rather than succeed — so after deploying, **confirm `Complex Ping Succeeded` actually appears in
  the logs** (and `Complex ping failed` is gone). If the server rejects sealing on
  `IObjectExporter`, drop back to `sign`+`keyExchange` only (packet integrity, no privacy).
- This is preferable to the server-side policy options above: it keeps the server hardened and
  brings the ping up to the session's normal auth level instead.
