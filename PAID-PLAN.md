# Paid plan (text only, not built)

The free plugin stays free and complete: all eight rules, quick fixes, the tool window and settings. Nothing that is free today moves behind a paywall.

## What a paid tier could add

JetBrains Marketplace supports freemium plugins: one plugin, with paid features turned on by a JetBrains license (`<product-descriptor optional="true">`). JetBrains handles checkout, trials, tax and invoices and keeps 15 %.

Context Drift Pro would add team features that a solo user does not need:

- Shared team rule packs: a checked-in `.context-drift.json` with house rules (required sections, banned commands, required test command per folder) that every IDE on the team enforces.
- A project report: one HTML or Markdown page with every finding across all modules, for a pull request or an audit.
- A CI gate generated from the same settings (GitHub Actions and GitLab snippets that run the CLI with the team's ignore list).

## Price

$12 a year personal, $29 a year organization, with the standard 30-day trial. The Marketplace's paid plugins sit between $10 and $39 a year; small single-purpose tools are usually $10 to $20.

## When to build it

Only after the free plugin shows demand: 1,000 downloads or 30 GitHub stars or 3 requests for team rules, whichever comes first. Before that, the honest income is GitHub Sponsors.

## Code changes when the time comes

1. `plugin.xml`: `<product-descriptor code="PCONTEXTDRIFT" release-date="YYYYMMDD" release-version="20261" optional="true"/>` (code from JetBrains).
2. A `LicensingFacade` check before the Pro actions run; the free inspections never check a license.
3. Tests for both states (licensed and not), and a note in the README that says which features are paid.
