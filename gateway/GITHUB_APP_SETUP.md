# Dedicated GitHub App setup for YARA Gateway

This setup is required before the Gateway can access a private repository. The existing ChatGPT GitHub App installation is not the YARA App and its private key must not be reused.

## App configuration

Create a new GitHub App under the project owner's GitHub account.

- Name: `YARA Collaboration Gateway` (or another globally available name)
- Homepage URL: `https://github.com/iman-shirin/yara-android`
- Callback URL: not required for the current static bearer-token gateway.
- Webhook: inactive; no webhook URL or secret is required for the initial read/write integration.
- Where can this GitHub App be installed? Only this account.

### Repository permissions

| Permission | Level |
|---|---|
| Metadata | Read-only |
| Contents | Read and write |
| Pull requests | Read and write |
| All other repository permissions | No access |

Subscribe to no events for the initial version. Do not grant Administration, Actions, Workflows, Secrets, Checks write, or organization permissions.

After creating the App:
1. Record its App ID.
2. Install it only on `iman-shirin/yara-android`.
3. Record the installation ID for that installation.
4. Generate a private key once.
5. Encode the PEM file as Base64 locally and put the encoded value directly into the deployment platform's secret manager. Do not upload the PEM or encoded key to GitHub, chat, issues, PRs or this repository.
6. Delete the downloaded PEM from any temporary/shared location after the secret is securely stored.

## Gateway environment variables

The `.railway/railway.ts` file declares the Railway Gateway and PostgreSQL resources, links the database URL, and preserves the secret variables below. Apply it only after connecting the Railway account and reviewing the infrastructure plan. Then set these secrets on the Gateway service:

- `DATABASE_URL`: private PostgreSQL connection URL, TLS required.
- `YARA_BOOTSTRAP_TOKEN`: cryptographically random secret, at least 32 characters; store only in the host secret manager.
- `GITHUB_APP_ID`: App ID from the App settings.
- `GITHUB_INSTALLATION_ID`: installation ID for this repository.
- `GITHUB_APP_PRIVATE_KEY_BASE64`: Base64-encoded PEM private key.
- `GITHUB_REPOSITORY`: exactly `iman-shirin/yara-android`.
- `PGSSLMODE`: `require`.

Do not put real values in `.env.example` or any Git-tracked file.

## Post-install verification

Before making the repository private, run all of the following through the deployed Gateway:
- App token exchange succeeds and the token expires/refreshes correctly.
- Read `README_FA.md`.
- Create an agent-scoped test branch.
- Create/update a harmless test file on that branch.
- Open a draft PR to `main`.
- Confirm writes to `main`, another agent's branch and protected paths are rejected.
- Revoke the test agent and confirm its token no longer works.
- Confirm the audit records identify the correct agent and commit SHA.

The current Railway configuration uses the `gateway` directory as the service root and its Dockerfile. Railway's current TypeScript Infrastructure as Code format is used rather than the deprecated `railway.json` Config-as-Code format.\n\nThe GitHub App's write permission is repository-wide. Branch and protected-path isolation is enforced by the Gateway, so a GitHub branch ruleset that blocks direct writes and requires owner review is still mandatory. Do not grant any App bypass in that ruleset.

## Private repository transition

Only after the full verification above:
1. Enable enforced main-branch rules requiring PRs and successful CI; prevent force-push/deletion and do not grant the App a bypass.
2. Confirm the existing ChatGPT GitHub connection will retain authorized access or replace it with the Gateway connection.
3. Change repository visibility to private in GitHub settings.
4. Repeat all provider read/write/revocation tests.

This document is a configuration guide. It does not mean the GitHub App has been created or installed.
