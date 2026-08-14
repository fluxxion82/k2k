# Security Policy

Passman is a local-only password manager: there is no server component, and vaults never leave the user's devices except over the LAN sync described in the [security model](https://github.com/fluxxion82/passmanShared#security-model). Vulnerabilities in the crypto, key storage, pairing, or sync layers are all in scope, in any of the `passmanShared`, `passmanClient`, or `k2k` repos.

## Reporting a vulnerability

**Please do not open a public issue for security reports.**

Email **fluxxion@gmail.com** with a description of the issue, the affected component, and reproduction steps if you have them. You should receive an acknowledgement within a week. Please allow a reasonable window for a fix to ship before public disclosure.

## What to expect

- The project is maintained by one person and has **not** had an independent security audit.
- Only the latest release is supported; cross-version sync is explicitly unsupported.
- Known, accepted limits (for example, the RSA-2048 transport identity) are documented in the security model — reports about documented accepted risks are still welcome if they change the practical picture.
