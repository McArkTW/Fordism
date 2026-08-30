// Wire DTOs for the fordism-core workflow API (tw.mcark.tony.fordism.web.Views).

/** One workflow in the list. `templates` is what its steps run, for filtering. */
export type WorkflowSummary = {
  name: string;
  strategy: string;
  steps: number;
  description: string;
  tags: string[];
  templates: string[];
};

/** One run parameter, as core parsed it. A bare name in the YAML arrives with defaults filled in. */
export type WorkflowParameter = {
  name: string;
  label: string;
  type: 'text' | 'textarea' | 'number';
  required: boolean;
  defaultValue: string;
  help: string;
};

/** One step, as core parsed it — the outline the editor renders. */
export type WorkflowStep = {
  id: string;
  template: string;
  dependsOn: string[];
  forEach: string;
  when: string;
  includePreviousResult: boolean;
  timeoutSeconds: number;
  /** Egress core will impose: 'none' | 'fordism-only' | 'full'. A step that omits it gets 'fordism-only'. */
  network: string;
};

/**
 * The parsed shape of a workflow. Core returns this from get, save and validate, so the outline
 * always reflects what the engine read — the app never parses YAML itself.
 */
export type WorkflowParsed = {
  name: string;
  description: string;
  strategy: string;
  tags: string[];
  maxIterations: number;
  parameters: WorkflowParameter[];
  steps: WorkflowStep[];
  generator: string;
};

/** The parsed shape plus the raw YAML, which only `get` carries. */
export type WorkflowDetail = WorkflowParsed & { yaml: string };

/** Whether a workflow can start, and the first reason it cannot. */
export type Preflight = { ready: boolean; problem: string };

/** Response from starting a run. */
export type RunStarted = {
  runId: string;
  workflow: string;
  state: string;
};

/** A file staged for upload — `name` is editable before it is zipped. */
export type StagedFile = { name: string; file: File };
