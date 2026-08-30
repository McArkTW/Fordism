import type { EditorView } from '@codemirror/view';
import {
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

/**
 * A YAML editor: CodeMirror 6, imported dynamically so it splits into its own chunk. Until it
 * arrives the textarea below is the editor — which also means the page still works if the chunk
 * fails to load.
 */
@Component({
  selector: 'yaml-editor',
  host: { class: 'block' },
  template: `
    <div class="h-full w-full overflow-hidden rounded-lg border border-border bg-background">
      @if (!ready()) {
        <textarea
          spellcheck="false"
          class="h-full w-full resize-none bg-transparent p-3 font-mono text-[13px] leading-relaxed text-current outline-none"
          [value]="value()"
          (input)="changed.emit($any($event.target).value)"
        ></textarea>
      }
      <div #host class="h-full w-full" [class.hidden]="!ready()"></div>
    </div>
  `,
})
export class YamlEditor implements OnDestroy {
  value = input<string>('');
  changed = output<string>();

  private host = viewChild.required<ElementRef<HTMLDivElement>>('host');

  ready = signal(false);
  private view: EditorView | null = null;

  constructor() {
    void this.mount();
    // Keep the editor in step when the value is replaced from outside (loading a workflow),
    // without echoing back the edits it just emitted.
    effect(() => {
      const next = this.value();
      const view = this.view;
      if (view && view.state.doc.toString() !== next) {
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: next },
        });
      }
    });
  }

  private async mount(): Promise<void> {
    const [
      { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter },
      { EditorState },
      { yaml },
      { indentUnit, foldGutter, indentOnInput },
      { defaultKeymap, history, historyKeymap },
      { searchKeymap, highlightSelectionMatches },
      { oneDark },
    ] = await Promise.all([
      import('@codemirror/view'),
      import('@codemirror/state'),
      import('@codemirror/lang-yaml'),
      import('@codemirror/language'),
      import('@codemirror/commands'),
      import('@codemirror/search'),
      import('@codemirror/theme-one-dark'),
    ]);

    const view = new EditorView({
      parent: this.host().nativeElement,
      state: EditorState.create({
        doc: this.value(),
        extensions: [
          lineNumbers(),
          highlightActiveLine(),
          highlightActiveLineGutter(),
          highlightSelectionMatches(),
          foldGutter(),
          history(),
          indentOnInput(),
          // YAML is indentation-structured, so two spaces and visible guides do more here than
          // any amount of colour.
          indentUnit.of('  '),
          keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap]),
          yaml(),
          oneDark,
          EditorView.lineWrapping,
          EditorView.theme({
            '&': { height: '100%', fontSize: '13px' },
            '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
            '&.cm-focused': { outline: 'none' },
          }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              this.changed.emit(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    this.view = view;
    this.ready.set(true);
  }

  ngOnDestroy(): void {
    this.view?.destroy();
  }
}
