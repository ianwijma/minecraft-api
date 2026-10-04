#!/usr/bin/env node
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createFixture, operations } = require('./browser-fixture.cjs');

async function openOperation(page, operationId) {
  const search = page.getByTestId('operation-search');
  await search.fill(operationId);
  const card = page.locator(`[data-testid="operation-card"][data-operation-id="${operationId}"]`);
  await card.waitFor({ state: 'visible' });
  await card.click();
  await page.getByRole('dialog').waitFor({ state: 'visible' });
}

async function waitFor(predicate, label, timeoutMs = 15_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

const dashboard = path.resolve(__dirname, '..');
const postCalls = (calls, requestPath) => calls.filter(call => call.method === 'POST' && call.path === requestPath);
function playwright() {
  try { return require('playwright'); }
  catch (first) {
    try { return require('/opt/codex/cua_node/lib/node_modules/playwright'); }
    catch { throw new Error(`Install the dashboard Playwright devDependency before running this suite. ${first.message}`); }
  }
}

function build(basePath, output) {
  if (process.env.MAPI_BROWSER_SKIP_BUILD === '1') {
    const existing = path.join(dashboard, 'out');
    if (!fs.existsSync(existing)) throw new Error('MAPI_BROWSER_SKIP_BUILD=1 but dashboard/out does not exist');
    fs.cpSync(existing, output, { recursive: true });
    return;
  }
  const env = { ...process.env };
  delete env.NEXT_PUBLIC_BASE_PATH;
  if (basePath) env.NEXT_PUBLIC_BASE_PATH = basePath;
  const result = spawnSync('npx', ['next', 'build', '--webpack'], { cwd: dashboard, env, stdio: 'inherit', shell: process.platform === 'win32' });
  if (result.status !== 0) throw new Error(`Next.js static build failed (${basePath || 'root'} base path)`);
  fs.cpSync(path.join(dashboard, 'out'), output, { recursive: true });
}

async function checkStaticExport(browser, label, staticDir, basePath, screenshotDir) {
  const fixture = createFixture(staticDir, basePath);
  const urls = await fixture.start();
  const errors = [];
  const pageErrors = [];
  const consoleErrors = [];
  const apiFailures = [];
  const httpFailures = [];
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ text: message.text(), location: message.location() }); });
  page.on('response', response => {
    const url = new URL(response.url());
    if (response.status() >= 400) httpFailures.push({ status: response.status(), url: response.url() });
    if (url.pathname.startsWith('/api/v1/') && response.status() >= 400) apiFailures.push({ status: response.status(), path: url.pathname });
  });
  try {
    await page.goto(urls.appUrl, { waitUntil: 'networkidle' });
    await page.getByTestId('dashboard-ready').waitFor({ state: 'visible', timeout: 30_000 });
    const browserProof = await page.evaluate(async ({ apiUrl }) => {
      const headers = { Authorization: 'Bearer browser-fixture-token' };
      const health = await fetch(`${apiUrl}/api/v1/health`, { headers });
      const healthBody = await health.json();
      if (!health.ok || healthBody.protocolVersion !== 1 || health.headers.get('x-mapi-protocol-version') !== '1') throw new Error('browser CORS health request failed');
      const denied = await fetch(`${apiUrl}/api/v1/health`, { headers: { Authorization: 'Bearer invalid-fixture-token' } });
      if (denied.status !== 401) throw new Error('fixture did not preserve bearer authentication');
      const events = await fetch(`${apiUrl}/api/v1/events/stream?cursor=40`, { headers, signal: AbortSignal.timeout(3000) });
      const reader = events.body.getReader();
      const decoder = new TextDecoder();
      let streamText = '';
      while (!streamText.includes('id: 42') && streamText.length < 4096) {
        const part = await reader.read();
        if (part.done) break;
        streamText += decoder.decode(part.value);
      }
      await reader.cancel();
      if (!events.ok || !streamText.includes('event-gap droppedUpTo=41') || !streamText.includes('id: 42')) throw new Error('browser SSE cursor/gap path failed');
      const accepted = await fetch(`${apiUrl}/api/v1/process/shutdown`, { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: '{}' });
      const job = await accepted.json();
      if (accepted.status !== 202 || job.jobId !== 'fixture-job-1') throw new Error('browser 202 job acceptance failed');
      const followRunning = await fetch(`${apiUrl}/api/v1/jobs/fixture-job-1`, { headers });
      if ((await followRunning.json()).state !== 'RUNNING') throw new Error('browser job pending/running state was not surfaced');
      const followComplete = await fetch(`${apiUrl}/api/v1/jobs/fixture-job-1`, { headers });
      if ((await followComplete.json()).state !== 'SUCCEEDED') throw new Error('browser job completion state was not surfaced');
      return { ok: true };
    }, { apiUrl: urls.apiUrl });
    if (!browserProof.ok) throw new Error('Real browser fetch fixture proof failed');
    const form = page.getByTestId('dashboard-ready').getByTestId('connect-form');
    await form.waitFor({ state: 'visible' });

    const name = form.getByTestId('connection-name');
    const endpoint = form.getByTestId('connection-address');
    const token = form.getByTestId('connection-token');
    await name.fill(`Browser ${label}`);
    await endpoint.fill(urls.apiUrl);
    await token.fill('browser-fixture-token');
    const remember = form.getByTestId('remember-token');
    if (await remember.count()) await remember.uncheck();
    const connect = form.getByTestId('connect-submit');
    const initialCallCount = fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.authorization === 'Bearer browser-fixture-token').length;
    await connect.click();
    await page.getByTestId('operation-card').first().waitFor({ state: 'visible', timeout: 30_000 });
    await waitFor(() => fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.authorization === 'Bearer browser-fixture-token').length > initialCallCount, 'connection probe');
    await page.screenshot({ path: path.join(screenshotDir, `${label.replace(/[^a-z0-9]+/gi, '-')}-connected.png`), fullPage: true });

    const cards = page.getByTestId('operation-card');
    await cards.first().waitFor({ state: 'visible', timeout: 30_000 });
    const displayed = await cards.count();
    if (displayed !== 48) throw new Error(`Expected all 48 operation cards; found ${displayed}`);
    for (const operation of operations) {
      if (await page.getByText(operation.label, { exact: true }).count() < 1) throw new Error(`Missing operation card label: ${operation.id} (${operation.label})`);
    }

    const savedProfile = await page.evaluate(() => JSON.parse(localStorage.getItem('mapi.dashboard.profiles') || '{}'));
    if (savedProfile.profiles?.[0]?.token) throw new Error('Token opt-out still persisted the bearer token');
    if (savedProfile.profiles?.[0]?.rememberToken !== false) throw new Error('Token opt-out was not persisted');

    const search = page.getByTestId('operation-search');
    await search.fill('getHealth');
    if (await cards.count() !== 1) throw new Error('Operation search did not narrow the catalog to one operation');
    await search.fill('');
    await page.getByRole('button', { name: 'Available only' }).click();
    const availableCount = await cards.count();
    if (availableCount < 1 || availableCount > 48) throw new Error(`Capability filter returned an invalid count: ${availableCount}`);
    await page.getByRole('button', { name: 'All actions' }).click();

    await openOperation(page, 'getHealth');
    const actionDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Health', exact: true }) });
    let healthCalls = fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.method === 'GET').length;
    await actionDialog.getByRole('button', { name: 'Run action' }).click();
    healthCalls++;
    await waitFor(() => fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.method === 'GET').length >= healthCalls, 'health action');
    await page.waitForFunction(() => document.querySelector('[data-testid="latest-response"]')?.textContent?.includes('"status": "ok"'), null, { timeout: 10_000 });
    await actionDialog.getByRole('button', { name: 'Save preset' }).click();
    await page.getByText('Preset saved.').waitFor();
    await actionDialog.locator('[data-record-id]').last().getByRole('button', { name: /^Save Health from / }).click();
    if (!(await actionDialog.locator('[data-record-id]').first().evaluate(el => el.classList.contains('is-saved')))) throw new Error('Saved calls are not ordered ahead of recent calls');
    await actionDialog.locator('[data-record-id]').first().getByRole('button', { name: /^Load fields for Health from / }).click();
    await page.getByText('Fields loaded.').waitFor();
    const healthHistoryCount = await actionDialog.locator('[data-record-id]').count();
    const priorHealthRecordIds = await actionDialog.locator('[data-record-id]').evaluateAll(rows => rows.map(row => row.dataset.recordId));
    await actionDialog.locator('[data-record-id]').first().getByRole('button', { name: /^Rerun Health from / }).click();
    healthCalls++;
    await waitFor(() => fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.method === 'GET').length >= healthCalls, 'health rerun');
    await page.waitForFunction(count => document.querySelector('dialog[open] .action-history-list')?.querySelectorAll('[data-record-id]').length > count, healthHistoryCount, { timeout: 10_000 });
    const rerunRecordId = await actionDialog.locator('[data-record-id]').evaluateAll((rows, oldIds) => rows.map(row => row.dataset.recordId).find(id => !oldIds.includes(id)), priorHealthRecordIds);
    if (!rerunRecordId) throw new Error('Rerun did not create a new history record');
    const rerunRecord = actionDialog.locator(`[data-record-id="${rerunRecordId}"]`);
    const rerunSnapshot = await rerunRecord.innerText();
    if (!rerunSnapshot.includes('Request · GET /api/v1/health') || !rerunSnapshot.includes('Response · 200')) throw new Error(`Rerun request/response snapshot was incomplete: ${rerunSnapshot}`);
    await page.getByRole('button', { name: 'Close action' }).click();

    await openOperation(page, 'requestShutdown');
    const shutdownDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Shutdown', exact: true }) });
    await shutdownDialog.getByRole('button', { name: 'Run action' }).click();
    await page.waitForFunction(() => document.querySelector('[data-testid="latest-response"]')?.textContent?.includes('"state": "SUCCEEDED"'), null, { timeout: 15_000 });
    if (fixture.state.jobPolls < 2) throw new Error(`HTTP 202 action did not follow job state to completion (${fixture.state.jobPolls} polls)`);
    await page.getByRole('button', { name: 'Close action' }).click();

    await openOperation(page, 'createWorld');
    const createDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Create world', exact: true }) });
    await createDialog.getByLabel('levelId').fill('browser-world');
    const worldPollsBefore = fixture.state.worldPolls;
    await createDialog.getByRole('button', { name: 'Run action' }).click();
    await waitFor(() => fixture.state.worldPolls >= worldPollsBefore + 2, 'world creation phase completion');
    await page.waitForFunction(() => document.querySelector('[data-testid="latest-response"]')?.textContent?.includes('"phase": "ACTIVE"'), null, { timeout: 10_000 });
    const createCall = postCalls(fixture.state.calls, '/api/v1/client/worlds/create').at(-1);
    if (!createCall?.body || createCall.body.levelId !== 'browser-world' || Object.hasOwn(createCall.body, 'seed')) throw new Error(`Optional request fields were serialized incorrectly: ${JSON.stringify(createCall?.body)}`);
    await page.getByRole('button', { name: 'Close action' }).click();

    await openOperation(page, 'moveWaypoints');
    const moveDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Move waypoints', exact: true }) });
    await moveDialog.getByRole('button', { name: '+ Add item' }).click();
    if (!(await moveDialog.getByLabel('Include').count())) throw new Error('Nested optional object fields were not rendered');
    const moveButton = moveDialog.getByRole('button', { name: 'Run action' });
    const moveCountBefore = postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length;
    await moveButton.click();
    await waitFor(() => postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length >= moveCountBefore + 1, 'first movement request');
    await moveButton.click();
    await waitFor(() => postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length >= moveCountBefore + 2, 'two movement requests');
    const movementCalls = postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints');
    const inputLeases = postCalls(fixture.state.calls, '/api/v1/client/control/lease');
    if (movementCalls.length < 2 || !movementCalls.every(call => fixture.state.issuedLeaseIds.includes(call.body?.leaseId) && call.body.leaseId !== '[REDACTED]')) throw new Error(`An issued input lease was not attached to every movement request: ${JSON.stringify(movementCalls.map(call => call.body))}`);
    if (inputLeases.length !== 1) throw new Error(`Expected one reused input lease; observed ${inputLeases.length}`);
    if (movementCalls.some(call => Object.hasOwn(call.body.waypoints[0], 'yaw'))) throw new Error('Omitted nested optional fields were sent in the request');
    let movementCount = movementCalls.length;
    for (let i = 0; i < 8; i++) {
      await moveButton.click();
      movementCount++;
      await waitFor(() => postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length >= movementCount, 'movement history run');
    }
    const recent = moveDialog.locator('[data-record-id]');
    await page.waitForFunction(() => document.querySelector('dialog[open] .action-history-list')?.querySelectorAll('[data-record-id]').length >= 10, null, { timeout: 10_000 });
    const historyList = moveDialog.locator('.action-history-list');
    const historyBounds = await historyList.evaluate(el => ({ client: el.clientHeight, scroll: el.scrollHeight }));
    if (historyBounds.client <= 0 || historyBounds.scroll <= historyBounds.client) throw new Error(`Action history is not independently scrollable: ${JSON.stringify(historyBounds)}`);
    const anchor = await historyList.evaluate(el => {
      el.scrollTop = Math.min(300, el.scrollHeight - el.clientHeight);
      const item = [...el.querySelectorAll('[data-record-id]')].find(row => row.getBoundingClientRect().bottom > el.getBoundingClientRect().top);
      return item ? { id: item.dataset.recordId, top: item.getBoundingClientRect().top } : null;
    });
    if (!anchor) throw new Error('Could not establish history scroll anchor');
    const historyCountBeforeAnchor = await recent.count();
    await moveButton.click();
    movementCount++;
    await waitFor(() => postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length >= movementCount, 'anchored history run');
    await page.waitForFunction(count => (document.querySelector('dialog[open] .action-history-list')?.querySelectorAll('[data-record-id]').length ?? 0) > count, historyCountBeforeAnchor, { timeout: 10_000 });
    const anchoredTop = await historyList.locator(`[data-record-id="${anchor.id}"]`).evaluate(el => el.getBoundingClientRect().top);
    if (Math.abs(anchoredTop - anchor.top) > 3) throw new Error(`Appending history moved the visible anchor by ${anchoredTop - anchor.top}px`);
    await page.setViewportSize({ width: 390, height: 844 });
    const mobileModalHeight = await moveDialog.evaluate(el => el.getBoundingClientRect().height);
    if (mobileModalHeight > 844) throw new Error(`Mobile action dialog exceeds viewport: ${mobileModalHeight}px`);
    await page.screenshot({ path: path.join(screenshotDir, `${label.replace(/[^a-z0-9]+/gi, '-')}-mobile-action.png`), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.getByRole('button', { name: 'Close action' }).click();

    fixture.state.mode = 'delete-error';
    await openOperation(page, 'deleteWorld');
    const deleteDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Delete world', exact: true }) });
    await deleteDialog.getByLabel('levelId').fill('fixture-world');
    await deleteDialog.getByLabel('confirm').check();
    await deleteDialog.getByRole('button', { name: 'Run action' }).click();
    await page.getByText(/FIXTURE_FAILURE: fixture operation failed/).waitFor({ timeout: 15_000 });
    if (!(await page.getByTestId('latest-response').innerText()).includes('FIXTURE_FAILURE')) throw new Error('Structured operation error was not shown in the response inspector');
    fixture.state.mode = 'normal';
    await page.getByRole('button', { name: 'Close action' }).click();

    await openOperation(page, 'streamEvents');
    const streamDialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Event stream', exact: true }) });
    await streamDialog.locator('.schema-field').filter({ hasText: 'cursor' }).getByRole('checkbox').check();
    await streamDialog.getByLabel('Request.cursor', { exact: true }).fill('40');
    await streamDialog.getByRole('button', { name: 'Run action' }).click();
    await page.getByTestId('activity-list').getByText(/Event stream gap detected/).waitFor({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Close action' }).click();
    if (!fixture.state.calls.some(call => call.path === '/api/v1/events/stream' && call.query.cursor === '40')) throw new Error('UI did not apply the SSE cursor option');
    await page.getByRole('button', { name: 'Stop stream' }).click();
    await waitFor(() => fixture.state.streamClosed >= 1, 'SSE cancellation');
    if (fixture.state.streamClosed < 1) throw new Error('Stopping the SSE stream did not cancel its browser request');
    fixture.state.mode = 'event-flood';
    await page.getByRole('button', { name: 'Start stream' }).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="activity-list"] .event-row').length >= 200, null, { timeout: 20_000 });
    const eventRows = await page.getByTestId('activity-list').locator('.event-row').count();
    if (eventRows !== 200) throw new Error(`Activity panel should retain exactly 200 events, found ${eventRows}`);
    const activityBounds = await page.getByTestId('activity-list').evaluate(el => ({ client: el.clientHeight, scroll: el.scrollHeight }));
    if (activityBounds.client <= 0 || activityBounds.scroll <= activityBounds.client) throw new Error(`Activity list is not independently scrollable: ${JSON.stringify(activityBounds)}`);
    await page.getByRole('button', { name: 'Stop stream' }).click();
    await waitFor(() => fixture.state.streamClosed >= 2, 'flood stream cancellation');
    await page.getByRole('button', { name: 'Clear activity events' }).click();
    if (await page.getByTestId('activity-list').locator('.event-row').count() !== 0) throw new Error('Clear activity did not empty the event list');
    fixture.state.mode = 'normal';

    const tabCountBeforePopup = await page.getByTestId('connection-tab').count();
    await page.getByTestId('add-connection').click();
    const connectionDialog = page.getByTestId('connection-dialog');
    await connectionDialog.waitFor({ state: 'visible' });
    if (await page.getByTestId('connection-tab').count() !== tabCountBeforePopup) throw new Error('Opening the + popup created a pending connection tab');
    await connectionDialog.getByRole('button', { name: 'Cancel' }).click();
    if (await page.getByTestId('connection-tab').count() !== tabCountBeforePopup) throw new Error('Canceling the + popup changed connection tabs');

    fixture.state.mode = 'delay-health-failure';
    await page.getByTestId('add-connection').click();
    const delayedDialog = page.getByTestId('connection-dialog');
    await delayedDialog.getByTestId('connection-name').fill(`Canceled ${label}`);
    await delayedDialog.getByTestId('connection-address').fill(urls.apiUrl);
    await delayedDialog.getByTestId('connection-token').fill('browser-fixture-token');
    const delayedHealthCount = fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.method === 'GET').length;
    await delayedDialog.getByTestId('test-connection').click();
    await waitFor(() => fixture.state.calls.filter(call => call.path === '/api/v1/health' && call.method === 'GET').length > delayedHealthCount, 'delayed connection probe dispatched');
    await delayedDialog.getByRole('button', { name: 'Close connection dialog' }).click();
    await page.waitForTimeout(1300);
    fixture.state.mode = 'normal';
    if (await page.getByTestId('connection-tab').count() !== tabCountBeforePopup) throw new Error('A late canceled connection attempt created a profile');

    await page.getByTestId('add-connection').click();
    await page.getByTestId('connection-dialog').getByTestId('connection-name').fill(`Dedicated ${label}`);
    await page.getByTestId('connection-dialog').getByTestId('connection-address').fill(urls.apiUrl);
    await page.getByTestId('connection-dialog').getByTestId('connection-token').fill('dedicated-fixture-token');
    await page.getByTestId('connection-dialog').getByTestId('remember-token').check();
    await page.getByTestId('connection-dialog').getByTestId('connect-submit').click();
    await page.getByTestId('operation-card').first().waitFor({ timeout: 30_000 });
    await page.getByText('DEDICATED', { exact: true }).waitFor({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Available only' }).click();
    const dedicatedAvailable = await page.getByTestId('operation-card').count();
    if (dedicatedAvailable >= 48) throw new Error('Dedicated mode did not gate client-only capabilities');
    await page.getByRole('button', { name: 'All actions' }).click();
    await page.reload({ waitUntil: 'networkidle' });
    await page.getByTestId('dashboard-ready').waitFor({ state: 'visible' });
    const reloadedProfiles = await page.evaluate(() => JSON.parse(localStorage.getItem('mapi.dashboard.profiles') || '{}'));
    if (reloadedProfiles.profiles?.length !== 2 || reloadedProfiles.profiles?.[1]?.token !== 'dedicated-fixture-token') throw new Error('Remembered connection credentials did not persist after reload');
    await page.getByText('DEDICATED', { exact: true }).waitFor({ timeout: 15_000 });
    await openOperation(page, 'getHealth');
    if (await page.getByRole('dialog').locator('[data-record-id]').count() !== 0) throw new Error('Operation history leaked across connections');
    await page.getByRole('button', { name: 'Close action' }).click();
    const primaryProfileId = reloadedProfiles.profiles[0].id;
    await page.getByTestId(`select-${primaryProfileId}`).click();
    const reconnectDialog = page.getByTestId('connection-dialog');
    await reconnectDialog.getByTestId('connection-token').fill('browser-fixture-token');
    await reconnectDialog.getByTestId('connect-submit').click();
    await page.getByTestId('operation-card').first().waitFor({ state: 'visible', timeout: 30_000 });
    await openOperation(page, 'getHealth');
    const persistedRuns = page.getByRole('dialog').locator('[data-record-id]');
    if (await persistedRuns.count() < 3) throw new Error('Per-connection action history did not survive reload and reconnect');
    if (!(await persistedRuns.first().evaluate(el => el.classList.contains('is-saved')))) throw new Error('Saved operation records were not restored at the top after reload');
    if (await persistedRuns.filter({ hasText: 'Response · 200' }).count() < 1) throw new Error('Saved run response snapshot was not restored after reload');
    await page.getByRole('button', { name: 'Close action' }).click();

    await page.getByTestId('add-connection').click();
    const conflictDialog = page.getByTestId('connection-dialog');
    await conflictDialog.getByTestId('connection-name').fill(`Conflict ${label}`);
    await conflictDialog.getByTestId('connection-address').fill(urls.apiUrl);
    await conflictDialog.getByTestId('connection-token').fill('conflict-fixture-token');
    await conflictDialog.getByTestId('connect-submit').click();
    await conflictDialog.waitFor({ state: 'hidden' });
    await page.getByRole('heading', { name: `Conflict ${label}`, exact: true }).waitFor({ state: 'visible' });
    await page.getByTestId('operation-card').first().waitFor({ state: 'visible', timeout: 30_000 });
    const conflictProfile = await page.evaluate(() => JSON.parse(localStorage.getItem('mapi.dashboard.profiles') || '{}').profiles.find(profile => profile.name.startsWith('Conflict ')));
    fixture.state.mode = 'lease-conflict';
    await openOperation(page, 'moveWaypoints');
    const conflictAction = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Move waypoints', exact: true }) });
    await conflictAction.getByRole('button', { name: '+ Add item' }).click();
    const movementBeforeConflict = postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length;
    await conflictAction.getByRole('button', { name: 'Run action' }).click();
    await page.getByText(/LEASE_HELD: Another client holds the input lease/).waitFor({ timeout: 15_000 });
    if (!fixture.state.calls.some(call => call.method === 'POST' && call.path === '/api/v1/client/control/lease' && call.authorization === 'Bearer conflict-fixture-token') || postCalls(fixture.state.calls, '/api/v1/client/movement/waypoints').length !== movementBeforeConflict) throw new Error('Lease conflict did not stop the game operation safely');
    fixture.state.mode = 'normal';
    await page.getByRole('button', { name: 'Close action' }).click();
    await page.getByTestId(`remove-${conflictProfile.id}`).click();
    await page.getByTestId('remove-dialog').getByTestId('confirm-remove').click();

    const dedicatedProfileId = reloadedProfiles.profiles[1].id;
    await page.getByTestId(`remove-${dedicatedProfileId}`).click();
    const removal = page.getByTestId('remove-dialog');
    if (!(await removal.getByTestId('cancel-remove').evaluate(el => el === document.activeElement))) throw new Error('Removal dialog did not focus Cancel by default');
    await removal.getByTestId('cancel-remove').click();
    if (await page.getByTestId('connection-tab').count() !== 2) throw new Error('Canceling removal deleted a connection');
    await page.getByTestId(`remove-${dedicatedProfileId}`).click();
    await page.getByTestId('remove-dialog').getByTestId('confirm-remove').click();
    if (await page.getByTestId('connection-tab').count() !== 1) throw new Error('Confirming removal did not delete the selected connection');

    const firstCard = cards.first();
    await firstCard.click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible' });
    const layout = await dialog.evaluate(el => ({ height: el.getBoundingClientRect().height, listHeight: el.querySelector('.action-history-list')?.getBoundingClientRect().height ?? 0 }));
    if (layout.height > 850 || layout.listHeight < 80) throw new Error(`Action dialog is not bounded: ${JSON.stringify(layout)}`);
    await page.getByRole('button', { name: /close action/i }).click();

    const calls = fixture.state.calls;
    const apiCalls = calls.filter(call => call.path.startsWith('/api/v1/'));
    if (apiCalls.length < 6) throw new Error(`Connection flow did not use real browser fetch: only ${apiCalls.length} API requests`);
    if (!calls.some(call => call.authorization === 'Bearer browser-fixture-token' && call.path === '/api/v1/health')) throw new Error('Connection probe did not use the entered bearer token');
    if (!calls.some(call => call.method === 'OPTIONS' && call.requestedMethod === 'GET' && /authorization/.test(call.requestedHeaders ?? ''))) throw new Error('Cross-origin browser requests did not exercise Authorization preflight');
    for (const expected of [{ status: 401, path: '/api/v1/health' }, { status: 500, path: '/api/v1/client/worlds/delete' }, { status: 409, path: '/api/v1/client/control/lease' }]) {
      if (!apiFailures.some(failure => failure.status === expected.status && failure.path === expected.path)) throw new Error(`Expected fixture HTTP ${expected.status} from ${expected.path} was not observed`);
    }
    for (const { text, location } of consoleErrors) {
      const status = text.match(/status of (\d+)/)?.[1];
      if (!status || !apiFailures.some(failure => String(failure.status) === status)) errors.push(`console: ${text} at ${JSON.stringify(location)}`);
    }
    for (const message of pageErrors) {
      if (message === 'BodyStreamBuffer was aborted' && fixture.state.streamClosed > 0) continue;
      errors.push(`pageerror: ${message}`);
    }
    if (errors.length) throw new Error(`Browser errors: ${errors.join(' | ')}; HTTP failures: ${JSON.stringify(httpFailures)}`);
    console.log(`PASS ${label}: static export boot, browser CORS/auth fetch, connection probe and ${displayed} operation cards`);
  } catch (error) {
    await page.screenshot({ path: path.join(screenshotDir, `${label.replace(/[^a-z0-9]+/gi, '-')}-failure.png`), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    await context.close();
    await fixture.close();
  }
}

async function main() {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-dashboard-browser-'));
  const screenshotDir = path.resolve(process.env.MAPI_BROWSER_ARTIFACT_DIR || path.join(os.tmpdir(), 'mapi-dashboard-production'));
  fs.mkdirSync(screenshotDir, { recursive: true });
  const rootOut = path.join(temp, 'root');
  const baseOut = path.join(temp, 'preview');
  try {
    build('', rootOut);
    build('/preview', baseOut);
    const { chromium } = playwright();
    const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || (fs.existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined);
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'], ...(executablePath ? { executablePath } : {}) });
    try {
      await checkStaticExport(browser, 'root', rootOut, '', screenshotDir);
      await checkStaticExport(browser, 'basePath /preview', baseOut, '/preview', screenshotDir);
    } finally { await browser.close(); }
  } finally {
    if (fs.existsSync(baseOut)) fs.cpSync(baseOut, path.join(screenshotDir, 'preview-export'), { recursive: true, force: true });
    if (fs.existsSync(rootOut)) {
      fs.rmSync(path.join(dashboard, 'out'), { recursive: true, force: true });
      fs.cpSync(rootOut, path.join(dashboard, 'out'), { recursive: true });
    }
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

main().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
