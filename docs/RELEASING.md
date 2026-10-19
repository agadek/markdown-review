# Releasing

Releases are published to JetBrains Marketplace by the [Release workflow](../.github/workflows/release.yml) when a
version tag is pushed. The build side is ready; the steps below are the ones only the maintainer can do, because they
need a JetBrains account, a private key and repository secrets.

## One-time setup

### 1. JetBrains Marketplace vendor profile

Sign in at <https://plugins.jetbrains.com> with a JetBrains Account and create a vendor profile (personal is enough).
`plugin.xml` declares the vendor as `agadek` with the URL `https://github.com/agadek/markdown-review`; change the
`<vendor>` element if the profile uses a different name.

### 2. Signing certificate

Generate a private key and a self-signed certificate chain (from the
[plugin signing docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Keep `private_encrypted.pem` and its password outside the repository (a password manager). The certificate is valid
for 365 days; set a reminder to renew it.

### 3. Marketplace token

On the Marketplace profile, open **My Tokens**, generate a token and copy it right away (it is shown once).

### 4. GitHub secrets

In the repository's **Settings | Secrets and variables | Actions**, add:

| Secret | Value |
|---|---|
| `CERTIFICATE_CHAIN` | `base64 -i chain.crt` |
| `PRIVATE_KEY` | `base64 -i private.pem` |
| `PRIVATE_KEY_PASSWORD` | the password given to `openssl genpkey` |
| `PUBLISH_TOKEN` | the Marketplace token |

The signing docs say the Gradle plugin detects and decodes Base64 values, which avoids multi-line secrets.
`build.gradle.kts` reads the same four names from environment variables, so a local
`./gradlew signPlugin` or `publishPlugin` works with them exported in the shell.

### 5. First upload by hand

Marketplace accepts uploads from Gradle only after the plugin exists there, and the first version goes through
moderation. With the four variables exported locally:

```bash
./gradlew signPlugin
```

Upload `build/distributions/markdown-review-<version>-signed.zip` on <https://plugins.jetbrains.com/plugin/add>,
fill in the license and source code fields (next section), add the screenshots from `docs/screenshots/`
(`side-by-side-light.png`, `merged-light.png` and their dark versions), and wait for approval. The page text comes from
`<description>` and `<change-notes>` in `plugin.xml`, the icon from `pluginIcon.svg` and `pluginIcon_dark.svg`.

### 6. License on the listing

Marketplace requires a license link for every plugin, free ones included. PolyForm Noncommercial is not an OSI license,
so it is given as a custom license URL rather than picked from a list:

- License: `https://github.com/agadek/markdown-review/blob/main/LICENSE` (or
  `https://polyformproject.org/licenses/noncommercial/1.0.0`).
- Source code: `https://github.com/agadek/markdown-review`.
- Mention in the plugin page that commercial use needs a separate license (the description in `plugin.xml` already
  does).

## Each release

1. Update `<change-notes>` in `src/main/resources/META-INF/plugin.xml`.
2. Set `pluginVersion` in `gradle.properties`, for example `0.2.0-beta` or `1.0.0`, and merge to `main`.
3. Tag the merge commit and push the tag:

   ```bash
   git tag v0.2.0-beta
   git push origin v0.2.0-beta
   ```

The workflow fails if the tag does not equal `v<pluginVersion>` or a secret is missing. It runs the tests, builds,
runs `verifyPlugin`, signs, attaches the signed zip to a GitHub release named after the tag, and then publishes to
Marketplace. The release is created before the upload, so it has the plugin zip even when the upload fails, for
example with "already contains version" when that version was uploaded by hand. The signed zip is also kept as a
workflow artifact.

## Sharing a build without publishing

**Actions | Preview release | Run workflow** builds, verifies and signs the current `main` (it needs the three signing
secrets, not `PUBLISH_TOKEN`) and attaches the signed zip to a GitHub pre-release tagged `preview-<pluginVersion>`.
Nothing goes to Marketplace, and the tag does not start with `v`, so the Release workflow does not run. The download
link stays the same for a version:
`https://github.com/agadek/markdown-review/releases/download/preview-<version>/markdown-review-<version>-signed.zip`.
Running the workflow again replaces the zip. Install it with **Settings | Plugins | ⚙ | Install Plugin from Disk...**;
such an install does not update itself, so once the plugin is on Marketplace, uninstall it and install from there.

Channels: a version with a suffix (`0.2.0-beta`, `0.2.0-beta.2`) goes to the `beta` channel, a plain version
(`1.0.0`) to the default one. Beta users add the custom plugin repository
`https://plugins.jetbrains.com/plugins/beta/list` in **Settings | Plugins | ⚙ | Manage Plugin Repositories**.

## Not verified

These details come from JetBrains' docs as of October 2026 or are assumptions; check them on the first release:

- The upload form for a new plugin: the current docs do not describe its license field. The requirement for a license
  URL comes from a 2018 JetBrains announcement and the 2023 Marketplace notice on source code, so whether the form
  offers a free-text "custom license" option or only a URL field was not confirmed. If in doubt, ask
  plugins-admin@jetbrains.com.
- Whether Marketplace moderation has any objection to a noncommercial (source-available) license on a free plugin.
  Nothing found says so, but nothing confirms it either.
- Base64 decoding of `CERTIFICATE_CHAIN` and `PRIVATE_KEY`: taken from the signing docs, not tried with real secrets.
- Whether the first version on the `beta` channel can be the hand-uploaded one, or the first upload must go to the
  default channel. The docs say only that the first upload is manual.
