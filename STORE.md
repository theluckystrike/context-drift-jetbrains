# Releasing on the JetBrains Marketplace

Everything below needs the owner's JetBrains account. Nothing here was done by an agent.

## Owner, one time (about 20 minutes)

1. Open https://plugins.jetbrains.com and sign in with the JetBrains account that should own the plugin (top right, "Sign In").
2. Accept the JetBrains Marketplace Developer Agreement when the site asks for it (first upload or the profile page).
3. Create the vendor profile: avatar menu, "Add Vendor" (or https://plugins.jetbrains.com/vendor/new). Name "Zovo", URL https://github.com/theluckystrike/context-drift, a public support email. The plugin.xml vendor name is "Zovo"; it must match.
4. Create a permanent token: avatar menu, "My Tokens" (https://plugins.jetbrains.com/author/me/tokens), "Generate Token", name it "context-drift publish". Copy it once; it is shown one time. Store it in the password manager, not in the repo.

## Owner, first upload (about 10 minutes, then 1 to 3 working days of review)

The first version of a new plugin must be uploaded in the browser:

1. https://plugins.jetbrains.com, avatar menu, "Upload plugin".
2. Vendor: Zovo. File: `build/distributions/context-drift-jetbrains-0.1.0.zip` (a copy is in out/JB1-dist/ of the run folder). License: MIT, link https://github.com/theluckystrike/context-drift/blob/main/LICENSE.
3. Tags: Code tools, Linters, Markdown, AI. Pricing: Free.
4. Click "Upload". JetBrains reviews every new plugin by hand. Watch the email of the account.
5. After approval, on the plugin page, "Edit": add the four screenshots from `images/` (1440 px wide), the source code URL, the issue tracker URL and the GitHub Sponsors URL (https://github.com/sponsors/theluckystrike) as "Donation link".

## Orchestrator, later versions (5 minutes)

After the plugin exists, later versions go up from Gradle:

```bash
cd factory/products/context-drift-jetbrains
# bump pluginVersion in gradle.properties and add a CHANGELOG entry first
export PUBLISH_TOKEN='<the permanent token from step 4>'
./gradlew test verifyPlugin publishPlugin
```

`publishPlugin` uploads `build/distributions/*.zip` with the token. Optional signing: create a certificate as described at https://plugins.jetbrains.com/docs/intellij/plugin-signing.html and export CERTIFICATE_CHAIN, PRIVATE_KEY and PRIVATE_KEY_PASSWORD; the build reads them from the environment.

## Owner, paid plan (only after the free plugin has users; see PAID-PLAN.md)

1. https://plugins.jetbrains.com/docs/marketplace/how-to-start-selling-plugins.html: apply to sell (Sales Agreement, payout bank account, tax forms). This needs the owner's identity and tax data. Allow 1 to 2 weeks for JetBrains to set up the product code.
2. JetBrains assigns a product code. The orchestrator then adds `<product-descriptor code="..." release-date="..." release-version="..." optional="true"/>` to plugin.xml and the license check described in PAID-PLAN.md.
