# Concepts

> Stub. To be written in full; the decisions behind each term are in `docs/PLAN.md`.

- **Module**: provides capabilities (what the software is able to do) and event sources.
- **Ruleset**: rules that say what Praksis is willing to do and has to do. Rulesets are
  stored in git repositories.
- **Layer**: a ruleset in a stack. A private layer can add, replace, disable or patch the rules of
  a public one without editing it.
- **Grant**: what a layer's rules may call, with limits on arguments. Rules never get more than
  their grants.
- **Verifier, tests, shadow mode**: how a changed ruleset is checked before it takes effect.
