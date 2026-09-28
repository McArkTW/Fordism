/**
 * One piece of a transcript row, typed so it can be shown the way it reads best: prose as Markdown, a model's
 * reasoning dimmed, a tool call with its arguments, a tool's output as code, and a note for anything stored
 * elsewhere. `html` is filled in by the view for Markdown and thinking parts.
 */
export type TxPart = {
  kind: 'md' | 'thinking' | 'tool' | 'code' | 'note';
  text: string;
  name?: string;
  html?: string;
};

/** One transcript row: role and its parts, plus flat text and tool names; `meta` for a call header. */
export type TxEntry = {
  role: string;
  text: string;
  tools: string[];
  meta?: string;
  parts?: TxPart[];
};

type Json = Record<string, unknown>;

/**
 * Parses a task's transcript for display, in either of the two shapes the API can serve.
 *
 * <p>Core's proxy transcript (`llm.jsonl`, written when FORDISM_TRANSCRIPT is on) is one line per
 * model call, in the wire format of whichever tool made it; each becomes a call header, then the
 * messages it added, then the reply.
 *
 * <p>A task that was not proxied has only the tool's own session store, which is a different shape
 * per tool — claude-code writes `message.content[]` with a `type` discriminator, qwen-code writes
 * `message.parts[]` with no type at all. That is what {@link nativeEntries} reads, and it is why an
 * un-proxied task still shows a transcript rather than a wall of raw JSON.
 */
export function parseTranscript(text: string): TxEntry[] {
  const out: TxEntry[] = [];
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    let obj: Json;
    try {
      obj = JSON.parse(trimmed) as Json;
    } catch {
      out.push(raw(trimmed));
      continue;
    }
    if (isProxyLine(obj)) {
      out.push(...proxyEntries(obj));
    } else {
      out.push(...nativeEntries(obj));
    }
  }
  return out;
}

function raw(text: string): TxEntry {
  return { role: 'raw', text, tools: [], parts: [{ kind: 'code', text }] };
}

function note(text: string): TxEntry {
  return { role: 'note', text, tools: [], parts: [{ kind: 'note', text }] };
}

function isProxyLine(obj: Json): boolean {
  return (
    'new_messages' in obj ||
    'new_messages_blob' in obj ||
    ('call' in obj && 'format' in obj) ||
    'stopped' in obj
  );
}

function proxyEntries(line: Json): TxEntry[] {
  if (typeof line['stopped'] === 'string') {
    return [note(`Transcript stopped: ${line['stopped']}.`)];
  }
  const entries: TxEntry[] = [header(line)];
  const format = String(line['format'] ?? '');
  const added = line['new_messages'];
  if (Array.isArray(added)) {
    for (const m of added) {
      entries.push(...messageEntries(m));
    }
  } else if (typeof line['new_messages_blob'] === 'string') {
    entries.push(note(`Messages stored separately (${line['new_messages_blob']}).`));
  }
  const response = line['response'];
  if (response && typeof response === 'object') {
    entries.push(...responseEntries(format, response as Json));
  } else if (typeof line['response_blob'] === 'string') {
    entries.push(note(`Reply stored separately (${line['response_blob']}).`));
  }
  return entries;
}

function header(line: Json): TxEntry {
  const usage = (line['usage'] ?? {}) as Json;
  const tokens = [
    'input_tokens',
    'cache_creation_input_tokens',
    'cache_read_input_tokens',
    'output_tokens',
  ]
    .map((k) => Number(usage[k] ?? 0))
    .reduce((a, b) => a + b, 0);
  const parts = [
    `call ${line['call'] ?? '?'}`,
    callTime(line),
    line['model'] ? String(line['model']) : '',
    line['status'] !== undefined ? `HTTP ${line['status']}` : '',
    line['ms'] !== undefined ? `${Number(line['ms']).toLocaleString()} ms` : '',
    tokens ? `${tokens.toLocaleString()} tokens` : '',
  ];
  if (line['reset']) {
    parts.push('history rewritten — full history below');
  } else if (typeof line['from'] === 'number') {
    parts.push(`history cut to ${line['from']} message(s), then:`);
  }
  if (line['complete'] === false) {
    parts.push('stream cut short');
  }
  return {
    role: 'call',
    text: '',
    tools: [],
    meta: parts.filter(Boolean).join(' · '),
  };
}

/**
 * When the call ran, in the viewer's local time: core records `at` when the reply ended and `ms` how long
 * it took, so it started `ms` earlier. Empty when the line has no usable `at`.
 */
function callTime(line: Json): string {
  const end = new Date(String(line['at'] ?? ''));
  if (isNaN(end.getTime())) {
    return '';
  }
  const start = new Date(end.getTime() - Number(line['ms'] ?? 0));
  const clock = (d: Date) => d.toLocaleTimeString('en-GB', { hour12: false });
  return `${clock(start)}–${clock(end)}`;
}

/** The reply, wherever its format puts the message. */
function responseEntries(format: string, r: Json): TxEntry[] {
  switch (format) {
    case 'openai-chat': {
      const choices = r['choices'];
      const message = Array.isArray(choices) ? (choices[0] as Json)?.['message'] : undefined;
      return message ? messageEntries(message, 'assistant') : [];
    }
    case 'openai-responses': {
      const output = r['output'];
      return Array.isArray(output)
        ? output.flatMap((item) => messageEntries(item, 'assistant'))
        : [];
    }
    case 'bedrock-converse': {
      const message = (r['output'] as Json | undefined)?.['message'];
      return message ? messageEntries(message, 'assistant') : [];
    }
    case 'gemini': {
      const candidates = r['candidates'];
      const content = Array.isArray(candidates) ? (candidates[0] as Json)?.['content'] : undefined;
      return content ? messageEntries(content, 'assistant') : [];
    }
    default:
      return messageEntries({ role: r['role'] ?? 'assistant', content: r['content'] }, 'assistant');
  }
}

/** A row from its parts: flat `text` joins every non-tool part, `tools` lists the tool names. */
function entry(role: string, parts: TxPart[]): TxEntry[] {
  const kept = parts.filter((p) => p.text || p.kind === 'tool');
  if (!kept.length) {
    return [];
  }
  return [
    {
      role,
      text: kept
        .filter((p) => p.kind !== 'tool')
        .map((p) => p.text)
        .join('\n'),
      tools: kept.filter((p) => p.kind === 'tool').map((p) => p.name ?? 'tool'),
      parts: kept,
    },
  ];
}

/** One message in any of the six formats; Responses items that are not messages become their own rows. */
function messageEntries(value: unknown, defaultRole = 'event'): TxEntry[] {
  if (typeof value === 'string') {
    return entry(defaultRole, [{ kind: 'md', text: value }]);
  }
  if (!value || typeof value !== 'object') {
    return [];
  }
  const m = value as Json;
  switch (m['type']) {
    case 'function_call':
      return entry('assistant', [tool(m['name'], m['arguments'])]);
    case 'function_call_output':
      return entry('tool', [{ kind: 'code', text: stringify(m['output']) }]);
    case 'reasoning': {
      const summary = m['summary'];
      const text = Array.isArray(summary)
        ? summary.map((x) => String((x as Json)?.['text'] ?? '')).join('\n')
        : '';
      return entry('assistant', [{ kind: 'thinking', text }]);
    }
  }
  let role = String(m['role'] ?? defaultRole);
  if (role === 'model') {
    role = 'assistant';
  }
  const parts: TxPart[] = [];
  const content = m['content'] ?? m['parts'];
  if (typeof content === 'string') {
    parts.push({ kind: role === 'tool' ? 'code' : 'md', text: content });
  } else if (Array.isArray(content)) {
    for (const block of content) {
      parts.push(...blockParts(block));
    }
  }
  const calls = m['tool_calls'];
  if (Array.isArray(calls)) {
    for (const call of calls) {
      const fn = (call as Json)?.['function'] as Json | undefined;
      parts.push(tool(fn?.['name'], fn?.['arguments']));
    }
  }
  return entry(role, parts);
}

function tool(name: unknown, args: unknown): TxPart {
  return { kind: 'tool', name: String(name ?? 'tool'), text: pretty(args) };
}

/** The parts of one content block, in any format. */
function blockParts(value: unknown): TxPart[] {
  if (typeof value === 'string') {
    return [{ kind: 'md', text: value }];
  }
  if (!value || typeof value !== 'object') {
    return [];
  }
  const b = value as Json;
  if (b['$blob']) {
    return [{ kind: 'note', text: `[text, ${kb(b['bytes'])} — stored separately]` }];
  }
  if (b['$binary']) {
    return [
      {
        kind: 'note',
        text: `[${b['media_type'] ?? 'binary'}, ${kb(b['bytes'])}]`,
      },
    ];
  }
  switch (b['type']) {
    case 'text':
    case 'input_text':
    case 'output_text':
      return [{ kind: 'md', text: stringify(b['text']) }];
    case 'thinking':
      return [{ kind: 'thinking', text: stringify(b['thinking']) }];
    case 'tool_use':
      return [tool(b['name'], b['input'])];
    case 'tool_result':
      return [{ kind: 'code', text: contentText(b['content']) }];
    case 'image':
    case 'document': {
      const data = (b['source'] as Json | undefined)?.['data'];
      return typeof data === 'object' && data
        ? blockParts(data)
        : [{ kind: 'note', text: `[${b['type']}]` }];
    }
    case 'image_url':
    case 'input_image':
      return [{ kind: 'note', text: '[image]' }];
  }
  if (b['toolUse']) {
    const use = b['toolUse'] as Json;
    return [tool(use['name'], use['input'])];
  }
  if (b['toolResult']) {
    return [{ kind: 'code', text: contentText((b['toolResult'] as Json)['content']) }];
  }
  if (b['functionCall']) {
    const call = b['functionCall'] as Json;
    return [tool(call['name'], call['args'])];
  }
  if (b['functionResponse']) {
    const response = ((b['functionResponse'] as Json)['response'] ?? {}) as Json;
    return [
      {
        kind: 'code',
        text: stringify(response['output'] ?? response['error'] ?? response),
      },
    ];
  }
  if (b['reasoningContent']) {
    return [
      {
        kind: 'thinking',
        text: stringify((b['reasoningContent'] as Json)['text']),
      },
    ];
  }
  if (b['image'] || b['document']) {
    return [{ kind: 'note', text: '[attachment]' }];
  }
  if (typeof b['text'] === 'string') {
    return [{ kind: b['thought'] ? 'thinking' : 'md', text: b['text'] as string }];
  }
  return [];
}

/** Text of a tool result's content, which each format nests differently. */
function contentText(content: unknown): string {
  if (typeof content === 'string') {
    return content;
  }
  const flat = (parts: TxPart[]) =>
    parts
      .map((p) => p.text)
      .filter(Boolean)
      .join('\n');
  if (Array.isArray(content)) {
    return content
      .map((c) => flat(blockParts(c)) || stringify((c as Json)?.['json']))
      .filter(Boolean)
      .join('\n');
  }
  if (content && typeof content === 'object') {
    return flat(blockParts(content));
  }
  return '';
}

function stringify(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/** Tool arguments pretty-printed; a JSON string (OpenAI) is parsed first. Empty arguments show nothing. */
function pretty(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  let v = value;
  if (typeof v === 'string') {
    try {
      v = JSON.parse(v);
    } catch {
      return v as string;
    }
  }
  if (v && typeof v === 'object' && Object.keys(v as object).length === 0) {
    return '';
  }
  return JSON.stringify(v, null, 2);
}

function kb(bytes: unknown): string {
  const n = Number(bytes ?? 0);
  return n >= 1024 ? `${Math.round(n / 1024)} KB` : `${n} bytes`;
}

/**
 * One line of a tool's OWN session store, for a task that was not proxied.
 *
 * Two dialects, and reading only `content` is what made qwen rows render with a role and an empty
 * body — which looks like a transcript that was not captured rather than one that was not parsed.
 * claude-code writes `message.content[]` with a `type` discriminator; qwen-code writes
 * `message.parts[]`, where a part is text, a functionCall or a functionResponse depending on which
 * key it carries.
 */
function nativeEntries(obj: Json): TxEntry[] {
  const msg = (obj['message'] ?? {}) as Json;
  const role = String(obj['role'] ?? msg['role'] ?? obj['type'] ?? 'event');
  const parts: TxPart[] = [];
  const content = msg['content'] ?? obj['content'] ?? msg['parts'] ?? obj['parts'];
  if (typeof content === 'string') {
    parts.push({ kind: 'md', text: content });
  } else if (Array.isArray(content)) {
    for (const item of content) {
      if (typeof item === 'string') {
        parts.push({ kind: 'md', text: item });
        continue;
      }
      const part = item as Json;
      if (part['type'] === 'text') {
        parts.push({ kind: 'md', text: String(part['text'] ?? '') });
      } else if (part['type'] === 'thinking') {
        parts.push({ kind: 'thinking', text: String(part['thinking'] ?? '') });
      } else if (part['type'] === 'tool_use') {
        parts.push({
          kind: 'tool',
          name: String(part['name'] ?? 'tool'),
          text: part['input'] ? JSON.stringify(part['input'], null, 2) : '',
        });
      } else if (part['type'] === 'tool_result') {
        parts.push({ kind: 'code', text: toolResultText(part['content']) });
      } else if (part['functionCall']) {
        const call = part['functionCall'] as Json;
        parts.push({
          kind: 'tool',
          name: String(call?.['name'] ?? 'tool'),
          text: call?.['args'] ? JSON.stringify(call['args'], null, 2) : '',
        });
      } else if (part['functionResponse']) {
        const response = part['functionResponse'] as Json;
        const output = (response?.['response'] ?? {}) as Json;
        parts.push({
          kind: 'code',
          text: String(output['output'] ?? output['error'] ?? ''),
        });
      } else if (typeof part['text'] === 'string') {
        // qwen text part; `thought` marks reasoning rather than an answer.
        parts.push({
          kind: part['thought'] ? 'thinking' : 'md',
          text: String(part['text']),
        });
      }
    }
  }
  if (!parts.length && typeof obj['text'] === 'string') {
    parts.push({ kind: 'md', text: obj['text'] });
  }
  if (!parts.length && typeof obj['summary'] === 'string') {
    parts.push({ kind: 'md', text: obj['summary'] });
  }
  return entry(role, parts);
}

function toolResultText(result: unknown): string {
  if (typeof result === 'string') {
    return result;
  }
  if (Array.isArray(result)) {
    return result.map((x) => String((x as Json)?.['text'] ?? '')).join('');
  }
  return '';
}
