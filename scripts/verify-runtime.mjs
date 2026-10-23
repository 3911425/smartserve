import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { Client, environment, root, base, option } from './evaluation-client.mjs';

if (!['localhost', '127.0.0.1'].includes(new URL(base).hostname)) throw new Error('Runtime verification only accepts a local SmartServe endpoint.');
const mode = option('mode', 'native');
if (!['native', 'hybrid', 'docker'].includes(mode)) throw new Error('mode must be native, hybrid or docker');
const env = await environment();
const status = await new Client().api('/status');
assert.equal(status.businessRulesVersion, '2.1-20261023');
assert.equal(status.paymentMode, 'SIMULATED');
const checks = [];
for (const [username, password, role] of [
  [env.BOOTSTRAP_ADMIN_USER || 'admin', env.BOOTSTRAP_ADMIN_PASSWORD, 'ADMIN'],
  ['demo', env.BOOTSTRAP_CUSTOMER_PASSWORD, 'CUSTOMER'],
]) {
  const client = new Client();
  const login = await client.api('/auth/login', 'POST', { username, password });
  try {
    assert.equal(login.user.role, role);
    assert.equal((await client.api('/auth/me')).user.id, login.user.id);
    if (role === 'ADMIN') await client.api('/admin/customers');
    else {
      await assert.rejects(client.api('/admin/customers'), error => error.status === 403);
      const orders = await client.api('/orders/page');
      assert.equal(typeof orders.total, 'number');
      assert.equal(typeof orders.hasNext, 'boolean');
      for (const order of orders.items) {
        assert.equal(order.userId, login.user.id);
        assert.equal(typeof order.serviceOptions?.canCancel, 'boolean');
        assert.equal(typeof order.serviceOptions?.canApplyRefund, 'boolean');
      }
      assert.ok(Number((await client.api('/wallet')).balance) >= 0);
    }
    checks.push(`${role}: login, session and permissions`);
  } finally { await client.api('/auth/logout', 'POST'); }
  await assert.rejects(client.api('/auth/me'), error => error.status === 401);
}
await assert.rejects(new Client().api('/orders/page'), error => error.status === 401);
const picture = await fetch(base + '/files/images/smart-home-hero.png');
assert.equal(picture.status, 200);
const jarHash = crypto.createHash('sha256').update(await fs.readFile(path.join(root, 'backend/target/smartserve-backend-2.0.0.jar'))).digest('hex');
const row = { mode, measuredAt: new Date().toISOString(), passed: true, businessRulesVersion: status.businessRulesVersion, vectorStore: status.knowledge.vectorStore, modelEnabled: status.modelEnabled, modelProvider: status.modelMode, hostJarSha256: mode === 'docker' ? null : jarHash, checks, limitations: ['Read-only business checks plus login/logout; does not claim production load or full model quality validation.'] };
const output = path.join(root, 'data/evaluation/results/runtime-modes.json');
let report = { rows: [] };
try { report = JSON.parse(await fs.readFile(output, 'utf8')); } catch {}
report.rows = [...report.rows.filter(item => item.mode !== mode), row];
await fs.writeFile(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(row));
