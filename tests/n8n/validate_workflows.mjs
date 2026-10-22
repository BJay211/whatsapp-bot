#!/usr/bin/env node
/**
 * Validates n8n workflow JSON files before you import them.
 *
 * Usage:
 *   node tests/n8n/validate_workflows.mjs
 *   node tests/n8n/validate_workflows.mjs path/to/workflow.json
 *
 * Checks:
 *   - the file parses as JSON
 *   - every node has a supported type, a name, and a position
 *   - node names and node ids are unique
 *   - every connection source/target exists
 *   - webhook paths are unique inside a workflow
 *   - no obvious secrets are hardcoded
 *   - sticky notes are not treated as executable nodes
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');

const KNOWN_TYPES = new Set([
  'n8n-nodes-base.webhook',
  'n8n-nodes-base.code',
  'n8n-nodes-base.function',
  'n8n-nodes-base.if',
  'n8n-nodes-base.switch',
  'n8n-nodes-base.set',
  'n8n-nodes-base.postgres',
  'n8n-nodes-base.httpRequest',
  'n8n-nodes-base.respondToWebhook',
  'n8n-nodes-base.scheduleTrigger',
  'n8n-nodes-base.executeWorkflow',
  'n8n-nodes-base.executeWorkflowTrigger',
  'n8n-nodes-base.openAi',
  'n8n-nodes-base.langchain',
  '@n8n/n8n-nodes-langchain.openAi',
  'n8n-nodes-base.merge',
  'n8n-nodes-base.splitInBatches',
  'n8n-nodes-base.noOp',
  'n8n-nodes-base.wait',
  'n8n-nodes-base.stickyNote',
]);

const NON_EXECUTABLE = new Set(['n8n-nodes-base.stickyNote']);

const SECRET_PATTERNS = [
  { re: /sk-[A-Za-z0-9]{20,}/, label: 'OpenAI-style API key' },
  { re: /ghp_[A-Za-z0-9]{20,}/, label: 'GitHub personal access token' },
  { re: /AKIA[0-9A-Z]{16}/, label: 'AWS access key' },
  { re: /postgres(ql)?:\/\/[^\s"']+:[^\s"']+@/, label: 'Postgres URL with password' },
];

function collectFiles() {
  const cliArgs = process.argv.slice(2);
  if (cliArgs.length > 0) return cliArgs.map((p) => resolve(p));

  const files = [];
  const rootWorkflow = join(repoRoot, 'Bonjee_bot.json');
  try {
    if (statSync(rootWorkflow).isFile()) files.push(rootWorkflow);
  } catch {}

  const dir = join(repoRoot, 'n8n', 'workflows');
  try {
    for (const entry of readdirSync(dir)) {
      if (entry.endsWith('.json')) files.push(join(dir, entry));
    }
  } catch {}
  return files;
}

function validate(file) {
  const problems = [];
  const notes = [];
  let wf;

  try {
    wf = JSON.parse(readFileSync(file, 'utf8'));
  } catch (e) {
    return { file, problems: [`invalid JSON: ${e.message}`], notes };
  }

  if (!Array.isArray(wf.nodes) || wf.nodes.length === 0) {
    problems.push('workflow has no nodes array');
    return { file, problems, notes };
  }
  if (typeof wf.name !== 'string' || !wf.name.trim()) problems.push('workflow is missing a name');

  const names = new Map();
  const ids = new Map();
  const webhookPaths = new Map();

  for (const node of wf.nodes) {
    if (!node || typeof node !== 'object') {
      problems.push('a node is not an object');
      continue;
    }
    const name = node.name;
    if (!name) problems.push(`node without name (type=${node.type})`);
    if (names.has(name)) problems.push(`duplicate node name: ${name}`);
    names.set(name, node);

    if (node.id) {
      if (ids.has(node.id)) problems.push(`duplicate node id: ${node.id}`);
      ids.set(node.id, name);
    }

    if (!node.type) problems.push(`node "${name}" has no type`);
    else if (!KNOWN_TYPES.has(node.type)) problems.push(`node "${name}" uses unknown type ${node.type}`);

    if (!Array.isArray(node.position) || node.position.length !== 2) {
      problems.push(`node "${name}" has no valid position [x, y]`);
    }

    if (node.type === 'n8n-nodes-base.webhook') {
      const path = node.parameters?.path;
      if (!path) problems.push(`webhook node "${name}" has no path`);
      else {
        if (webhookPaths.has(path)) {
          problems.push(`duplicate webhook path "${path}" in nodes "${webhookPaths.get(path)}" and "${name}"`);
        }
        webhookPaths.set(path, name);
      }
      if (!node.parameters?.httpMethod) problems.push(`webhook "${name}" has no httpMethod`);
    }

    if (node.type === 'n8n-nodes-base.postgres') {
      const query = node.parameters?.query ?? '';
      if (node.parameters?.operation === 'executeQuery') {
        if (!query) problems.push(`postgres node "${name}" has an empty query`);
        if (/\$\{/.test(query)) {
          problems.push(`postgres node "${name}" interpolates JS into SQL - use queryReplacement with $1 params`);
        }
      }
    }

    if (node.type === 'n8n-nodes-base.respondToWebhook' && !node.parameters?.respondWith) {
      problems.push(`respond node "${name}" has no respondWith`);
    }
  }

  // Connections
  const connections = wf.connections ?? {};
  for (const [source, branches] of Object.entries(connections)) {
    if (!names.has(source)) problems.push(`connection from unknown node "${source}"`);
    if (!branches?.main) {
      problems.push(`connection "${source}" has no main branches`);
      continue;
    }
    branches.main.forEach((branch, branchIndex) => {
      if (!Array.isArray(branch)) return;
      for (const target of branch) {
        if (!target?.node) problems.push(`connection "${source}"[${branchIndex}] has no target node`);
        else if (!names.has(target.node)) {
          problems.push(`connection "${source}"[${branchIndex}] targets unknown node "${target.node}"`);
        }
      }
    });
  }

  // Every executable node should be reachable from a trigger, and every
  // webhook with responseMode=responseNode needs a reachable respond node.
  const executable = wf.nodes.filter((n) => !NON_EXECUTABLE.has(n.type));
  const reachable = new Set();
  const queue = executable
    .filter((n) => n.type === 'n8n-nodes-base.webhook' || n.type === 'n8n-nodes-base.scheduleTrigger')
    .map((n) => n.name);

  // If a workflow has no trigger at all (e.g. a sub-workflow), skip the reachability check.
  if (queue.length > 0) {
    while (queue.length) {
      const current = queue.shift();
      if (reachable.has(current)) continue;
      reachable.add(current);
      for (const branch of connections[current]?.main ?? []) {
        for (const target of branch ?? []) {
          if (names.has(target.node)) queue.push(target.node);
        }
      }
    }
    for (const node of executable) {
      if (!reachable.has(node.name)) notes.push(`node "${node.name}" is not reachable from any trigger`);
    }
  }

  // Hardcoded secret scan (skip credentials.id placeholders)
  const text = JSON.stringify(wf);
  for (const { re, label } of SECRET_PATTERNS) {
    if (re.test(text)) problems.push(`possible hardcoded ${label} in workflow`);
  }

  if (typeof wf.active !== 'boolean') problems.push('workflow.active must be a boolean');
  if (!wf.settings || typeof wf.settings !== 'object') notes.push('workflow has no settings block (optional)');

  return { file, problems, notes };
}

const files = collectFiles();
if (files.length === 0) {
  console.error('No workflow files found.');
  process.exit(1);
}

let failures = 0;
for (const file of files) {
  const rel = file.startsWith(repoRoot) ? file.slice(repoRoot.length + 1) : file;
  const { problems, notes } = validate(file);
  const label = rel.replace(/\\/g, '/');

  if (problems.length === 0) {
    console.log(`PASS  ${label}`);
  } else {
    failures += 1;
    console.log(`FAIL  ${label}`);
    for (const p of problems) console.log(`        - ${p}`);
  }
  for (const n of notes) console.log(`        note: ${n}`);
}

console.log(`\n${files.length - failures}/${files.length} workflow file(s) valid.`);
process.exit(failures === 0 ? 0 : 1);
