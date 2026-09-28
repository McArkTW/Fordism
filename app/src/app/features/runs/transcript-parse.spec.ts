import { describe, expect, it } from 'vitest';
import { parseTranscript, TxEntry } from './transcript-parse';

const line = (o: object) => JSON.stringify(o);
const rows = (entries: TxEntry[]) => entries.map((e) => `${e.role}|${e.text}|${e.tools.join(',')}`);

describe('parseTranscript — core proxy transcript', () => {
  it('Anthropic / Bedrock: a call header, the messages it added, then the reply with its tool use', () => {
    const out = parseTranscript(
      line({
        call: 1,
        format: 'anthropic',
        model: 'global.anthropic.claude-sonnet-5',
        status: 200,
        ms: 1200,
        usage: {
          input_tokens: 5,
          cache_creation_input_tokens: 100,
          cache_read_input_tokens: 0,
          output_tokens: 20,
        },
        new_messages: [
          { role: 'user', content: 'write the file' },
          {
            role: 'user',
            content: [
              {
                type: 'tool_result',
                tool_use_id: 't',
                content: [{ type: 'text', text: 'done' }],
              },
            ],
          },
        ],
        response: {
          role: 'assistant',
          content: [
            { type: 'text', text: 'ok' },
            { type: 'tool_use', name: 'Write', input: {} },
          ],
        },
      }),
    );
    expect(out[0].role).toBe('call');
    expect(out[0].meta).toContain('call 1');
    expect(out[0].meta).toContain('125 tokens');
    expect(out[0].meta).not.toMatch(/\d\d:\d\d:\d\d/); // no `at`, no time
    expect(rows(out.slice(1))).toEqual([
      'user|write the file|',
      'user|done|',
      'assistant|ok|Write',
    ]);
  });

  it('OpenAI Chat: reply from choices[0].message, tool calls named', () => {
    const out = parseTranscript(
      line({
        call: 2,
        format: 'openai-chat',
        new_messages: [{ role: 'tool', content: 'result text' }],
        response: {
          choices: [
            {
              message: {
                role: 'assistant',
                content: 'hi',
                tool_calls: [{ function: { name: 'bash' } }],
              },
            },
          ],
        },
      }),
    );
    expect(rows(out.slice(1))).toEqual(['tool|result text|', 'assistant|hi|bash']);
  });

  it('OpenAI Responses: function calls and outputs are their own rows', () => {
    const out = parseTranscript(
      line({
        call: 1,
        format: 'openai-responses',
        new_messages: [
          { role: 'user', content: [{ type: 'input_text', text: 'task' }] },
          { type: 'function_call_output', output: 'ran' },
        ],
        response: {
          output: [
            { type: 'function_call', name: 'shell', arguments: '{"cmd":"ls"}' },
            {
              type: 'message',
              role: 'assistant',
              content: [{ type: 'output_text', text: 'done' }],
            },
          ],
        },
      }),
    );
    expect(rows(out.slice(1))).toEqual([
      'user|task|',
      'tool|ran|',
      'assistant||shell',
      'assistant|done|',
    ]);
  });

  it('Bedrock Converse: toolUse and toolResult blocks', () => {
    const out = parseTranscript(
      line({
        call: 1,
        format: 'bedrock-converse',
        new_messages: [
          {
            role: 'user',
            content: [{ toolResult: { content: [{ text: 'file written' }] } }],
          },
        ],
        response: {
          output: {
            message: {
              role: 'assistant',
              content: [{ text: 'ok' }, { toolUse: { name: 'write' } }],
            },
          },
        },
      }),
    );
    expect(rows(out.slice(1))).toEqual(['user|file written|', 'assistant|ok|write']);
  });

  it('Gemini: parts, model role shown as assistant', () => {
    const out = parseTranscript(
      line({
        call: 1,
        format: 'gemini',
        new_messages: [{ role: 'user', parts: [{ text: 'hello' }] }],
        response: {
          candidates: [
            {
              content: {
                role: 'model',
                parts: [{ text: 'hi' }, { functionCall: { name: 'write_file' } }],
              },
            },
          ],
        },
      }),
    );
    expect(rows(out.slice(1))).toEqual(['user|hello|', 'assistant|hi|write_file']);
  });

  it('history markers, blobs and binaries read as notes, not blanks', () => {
    const out = parseTranscript(
      line({
        call: 3,
        format: 'anthropic',
        from: 1,
        complete: false,
        new_messages: [
          {
            role: 'user',
            content: [
              { type: 'text', text: 'see' },
              {
                type: 'image',
                source: {
                  type: 'base64',
                  data: {
                    $binary: 'sha256:x',
                    bytes: 5000,
                    media_type: 'image/png',
                  },
                },
              },
            ],
          },
          {
            role: 'user',
            content: [
              {
                type: 'tool_result',
                content: { $blob: 'sha256:y', bytes: 90000 },
              },
            ],
          },
        ],
      }),
    );
    expect(out[0].meta).toContain('history cut to 1');
    expect(out[0].meta).toContain('stream cut short');
    expect(out[1].text).toBe('see\n[image/png, 5 KB]');
    expect(out[2].text).toBe('[text, 88 KB — stored separately]');
  });

  it('the call header shows when the call ran: start (at minus ms) to end (at), in local time', () => {
    const at = '2026-09-17T06:17:22.000Z';
    const out = parseTranscript(
      line({ call: 7, format: 'anthropic', status: 200, ms: 3000, at, new_messages: [] }),
    );
    const clock = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
    expect(out[0].meta).toContain(`${clock('2026-09-17T06:17:19.000Z')}–${clock(at)}`);
  });
});

describe('parseTranscript — parts for display', () => {
  it('prose is Markdown, tool calls keep their arguments, tool output is code, reasoning is marked', () => {
    const out = parseTranscript(
      line({
        call: 1,
        format: 'anthropic',
        new_messages: [
          {
            role: 'user',
            content: [{ type: 'tool_result', content: 'ls output' }],
          },
        ],
        response: {
          content: [
            { type: 'thinking', thinking: 'plan' },
            { type: 'text', text: '**done**' },
            {
              type: 'tool_use',
              name: 'Write',
              input: { path: 'result/result.json' },
            },
          ],
        },
      }),
    );
    expect(out[1].parts).toEqual([{ kind: 'code', text: 'ls output' }]);
    expect(out[2].parts?.map((p) => p.kind)).toEqual(['thinking', 'md', 'tool']);
    expect(out[2].parts?.[2]).toEqual({
      kind: 'tool',
      name: 'Write',
      text: JSON.stringify({ path: 'result/result.json' }, null, 2),
    });
  });
});

describe("parseTranscript — a tool's own transcript, for a task that was not proxied", () => {
  it("reads claude-code's message.content[]", () => {
    const text = line({
      type: 'assistant',
      message: {
        role: 'assistant',
        content: [
          { type: 'thinking', thinking: 'weighing it up' },
          { type: 'text', text: 'Done.' },
          { type: 'tool_use', name: 'Write', input: { path: 'a.txt' } },
        ],
      },
    });
    const [row] = parseTranscript(text);
    expect(row.role).toBe('assistant');
    expect(row.tools).toEqual(['Write']);
    expect(row.parts?.map((p) => p.kind)).toEqual(['thinking', 'md', 'tool']);
    expect(row.text).toContain('Done.');
  });

  it("reads qwen-code's message.parts[], which carry no type at all", () => {
    const text = line({
      role: 'assistant',
      message: {
        parts: [
          { text: 'thinking out loud', thought: true },
          { text: 'the answer' },
          { functionCall: { name: 'run_shell_command', args: { cmd: 'ls' } } },
        ],
      },
    });
    const [row] = parseTranscript(text);
    expect(row.tools).toEqual(['run_shell_command']);
    // Reading only `content` left every qwen row with a role and an empty body, which reads as a
    // transcript that was not captured rather than one that was not parsed.
    expect(row.text).toContain('the answer');
    expect(row.parts?.some((p) => p.kind === 'thinking')).toBe(true);
  });

  it('a line that is not JSON at all is shown raw, not guessed at', () => {
    const text = 'not json {';
    const [row] = parseTranscript(text);
    expect(row.role).toBe('raw');
    expect(row.text).toBe(text);
  });
});
