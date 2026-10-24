// Shared helpers for generating n8n workflow JSON.
// Kept separate so every workflow file is produced by the same builder and the
// output shape cannot drift between files.

let nodeCounter = 0;

export function resetIds(seed = 0) {
  nodeCounter = seed;
}

function nextId() {
  nodeCounter += 1;
  const n = String(nodeCounter).padStart(12, '0');
  return `00000000-0000-4000-8000-${n}`;
}

export function uuid() {
  return nextId();
}

/** Build one node. `pos` is [x, y]. */
export function node(name, type, typeVersion, parameters, pos = [0, 0], extra = {}) {
  return { parameters, id: uuid(), name, type, typeVersion, position: pos, ...extra };
}

export const PG_CRED = {
  postgres: { id: 'REPLACE_WITH_POSTGRES_CREDENTIAL_ID', name: 'Bonjee Postgres' },
};

/**
 * Postgres node running a parameterised query.
 * `${1}`..`${n}` placeholders are bound from the expression in queryReplacement,
 * which must evaluate to an ARRAY of values (n8n postgres v2.x contract).
 */
export function pg(name, query, replacementExpression, pos, extra = {}) {
  const options = replacementExpression ? { queryReplacement: replacementExpression } : {};
  return node(
    name,
    'n8n-nodes-base.postgres',
    '2.5',
    { operation: 'executeQuery', query, options },
    pos,
    { credentials: PG_CRED, ...extra }
  );
}

export function code(name, jsCode, pos, extra = {}) {
  return node(name, 'n8n-nodes-base.code', '2', { jsCode }, pos, extra);
}

export function webhook(name, path, pos, extra = {}) {
  return node(
    name,
    'n8n-nodes-base.webhook',
    '2',
    {
      httpMethod: 'POST',
      path,
      responseMode: 'responseNode',
      options: { rawBody: false, responseData: 'firstEntryJson' },
    },
    pos,
    { webhookId: uuid(), ...extra }
  );
}

export function respond(name, bodyExpression, statusCode = 200, pos = [0, 0]) {
  return node(
    name,
    'n8n-nodes-base.respondToWebhook',
    '1.1',
    {
      respondWith: 'json',
      responseBody: bodyExpression,
      options: { responseCode: statusCode },
    },
    pos
  );
}

export function ifNode(name, conditions, pos, extra = {}) {
  return node(
    name,
    'n8n-nodes-base.if',
    '2.2',
    {
      conditions: {
        options: { caseSensitive: true, leftValue: '', typeValidation: 'strict', version: 2 },
        conditions,
        combinator: 'and',
      },
      options: {},
    },
    pos,
    extra
  );
}

export function http(name, { method = 'POST', url, headers, body, pos = [0, 0], timeout = 60000, extra = {} }) {
  const params = {
    method,
    url,
    sendHeaders: true,
    headerParameters: { parameters: headers ?? [] },
    sendBody: body !== undefined,
    options: { timeout, redirect: { redirect: {} }, response: { response: { responseFormat: 'json' } } },
  };
  if (body !== undefined) {
    params.specifyBody = 'json';
    params.jsonBody = body;
  }
  return node(name, 'n8n-nodes-base.httpRequest', '4.2', params, pos, extra);
}

export function schedule(name, cronExpression, pos) {
  return node(
    name,
    'n8n-nodes-base.scheduleTrigger',
    '1.2',
    { rule: { interval: [{ field: 'cronExpression', expression: cronExpression }] } },
    pos
  );
}

export function executeWorkflow(name, workflowId, pos, mappingMode = 'passThrough') {
  return node(
    name,
    'n8n-nodes-base.executeWorkflow',
    '1.2',
    {
      source: 'database',
      workflowId: { __rl: true, value: workflowId, mode: 'id' },
      mode: 'once',
      options: { waitForSubWorkflow: true },
      workflowInputs: { mappingMode },
    },
    pos
  );
}

export function execTrigger(name, pos) {
  return node(name, 'n8n-nodes-base.executeWorkflowTrigger', '1.1', { inputSource: 'passthrough' }, pos);
}

export function errorTrigger(name, pos) {
  return node(name, 'n8n-nodes-base.errorTrigger', '1', {}, pos);
}

export function manual(name, pos) {
  return node(name, 'n8n-nodes-base.manualTrigger', '1', {}, pos);
}

export function sticky(name, content, pos, width = 380, height = 200, color = 6) {
  return node(name, 'n8n-nodes-base.stickyNote', '1', {
    content, height, width, color,
  }, pos);
}

export function noOp(name, pos) {
  return node(name, 'n8n-nodes-base.noOp', '1', {}, pos);
}

/** Compose a workflow object. `connections` is built with connect(). */
export function workflow(name, nodes, connections, meta = {}) {
  return {
    name,
    nodes,
    connections,
    active: false,
    settings: { executionOrder: 'v1', saveManualExecutions: true, callerPolicy: 'workflowsFromSameOwner' },
    versionId: uuid(),
    meta: { templateCredsSetupCompleted: false, ...meta },
    tags: [],
  };
}

/** Connection builder shared by every workflow. */
export function connector() {
  const map = {};
  return {
    map,
    /** from's output `outIndex` goes to `to`. */
    to(from, to, outIndex = 0) {
      map[from] ??= { main: [] };
      while (map[from].main.length <= outIndex) map[from].main.push([]);
      map[from].main[outIndex].push({ node: to, type: 'main', index: 0 });
      return this;
    },
    chain(...names) {
      for (let i = 0; i < names.length - 1; i += 1) this.to(names[i], names[i + 1]);
      return this;
    },
  };
}

export const OR_STD = { alwaysOutputData: false, executeOnce: false, retryOnFail: true, maxTries: 3, waitBetweenTries: 2000 };

export function onError(extra = {}) {
  return { ...OR_STD, ...extra };
}
