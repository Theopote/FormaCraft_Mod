# Install FormaCraft AI Design Authority Guard

This package is a proposed repository overlay; it has **not** been committed to GitHub.

Copy the contents of this directory into the **root** of `Theopote/FormaCraft_Mod`, preserving the directory structure, then review and commit through your normal PR workflow.

- `AGENTS.md`: always-on guidance for Codex and agents that load repository instructions.
- `.cursor/rules/formacraft-ai-design-authority.mdc`: always-on, short Cursor rule.
- `.cursor/skills/formacraft-design-authority-audit/SKILL.md`: detailed on-demand audit procedure for generation-related code.
- `docs/architecture/AI_DESIGN_AUTHORITY_GUARDRAILS.md`: developer contract, current findings, eval cases and CI strategy.

The rule and Skill are an extra layer. They cannot *guarantee* correct code alone. The hard enforcement should be deterministic plan-preservation tests and branch-protected CI.
