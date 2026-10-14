import { defineConfig, devices } from '@playwright/test';

// Point PAYGUARD_URL at a running payguard (docker compose up). In CI, the job starts it and sets the variable.
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  // In CI: failures show as annotations on the run page, and ui-report.json feeds the job summary.
  reporter: process.env.CI
    ? [['list'], ['github'], ['html', { open: 'never' }], ['json', { outputFile: 'ui-report.json' }]]
    : 'list',
  use: {
    baseURL: process.env.PAYGUARD_URL ?? 'http://localhost:8080',
    locale: 'en-GB',
    timezoneId: 'Europe/London',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    // The journeys again on a phone-sized screen with touch.
    { name: 'phone', testMatch: /journeys\.spec\.mjs/, use: { ...devices['Pixel 7'] } },
  ],
});
