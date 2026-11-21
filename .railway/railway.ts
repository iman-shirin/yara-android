import { defineRailway, github, postgres, preserve, project, service } from "railway/iac";

export default defineRailway(() => {
  const database = postgres("yara-gateway-postgres");

  const gateway = service("yara-universal-gateway", {
    source: github("iman-shirin/yara-android", {
      branch: "feat/yara-collaboration-hub-foundation",
      rootDirectory: "gateway",
    }),
    healthcheck: "/healthz",
    healthcheckTimeout: 300,
    env: {
      DATABASE_URL: database.env.DATABASE_URL,
      YARA_BOOTSTRAP_TOKEN: preserve(),
      GITHUB_APP_ID: preserve(),
      GITHUB_INSTALLATION_ID: preserve(),
      GITHUB_APP_PRIVATE_KEY_BASE64: preserve(),
      GITHUB_REPOSITORY: "iman-shirin/yara-android",
      PGSSLMODE: "disable",
      NODE_ENV: "production",
    },
  });

  return project("YARA Collaboration Infrastructure", {
    resources: [database, gateway],
  });
});
