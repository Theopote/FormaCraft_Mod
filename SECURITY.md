# Security Policy

## Supported versions

Security fixes are applied to the latest release on the default branch (`main`).

| Version | Supported |
|---------|-----------|
| latest on `main` | yes |
| older releases | best effort |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, use one of the following:

1. **GitHub Security Advisories** (preferred): open a [private vulnerability report](https://github.com/Theopote/FormaCraft_Mod/security/advisories/new) on this repository.
2. **Email**: contact the maintainers directly if private advisories are unavailable.

Include as much detail as possible:

- Description of the issue and potential impact
- Steps to reproduce
- Affected components (mod client, Python backend, configuration)
- Suggested fix (if any)

We aim to acknowledge reports within **7 days** and will keep you informed of progress.

## Scope notes

- API keys and secrets belong in `.env` or in-game settings — never commit them to the repository.
- The Python backend runs locally by default; do not expose it to the public internet without authentication and network hardening.
- Formacraft executes LLM-generated plans in Minecraft worlds; treat untrusted prompts and backends with appropriate caution in multiplayer or shared environments.
