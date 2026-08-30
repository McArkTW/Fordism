import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { apiError } from '../../core/api-error';
import { SkillsService } from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { SKILL_LIST, SKILL_VIEW } from './skill-nav';

/**
 * Write a skill, or replace one wholesale by uploading its folder.
 *
 * <p>One page for both: creating and editing differ only in whether the name is fixed and whether
 * there is content to load first. A second component would be the same form twice.
 */
@Component({
  selector: 'app-skill-edit',
  imports: [
    FormsModule,
    RouterLink,
    Icon,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmSpinner,
    HlmTextarea,
  ],
  templateUrl: './skill-edit.html',
})
export class SkillEdit {
  private service = inject(SkillsService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private toasts = inject(Toasts);

  protected readonly listPath = SKILL_LIST;
  protected readonly viewPath = SKILL_VIEW;

  /** No `name` in the URL means this page is creating one. */
  readonly creating = signal(true);
  readonly loading = signal(false);
  readonly loadError = signal('');
  readonly saving = signal(false);
  readonly name = signal('');
  readonly content = signal(NEW_SKILL);
  /** The plugin that owns the skill being edited, if any — an edit here is undone by its sync. */
  readonly owner = signal('');

  constructor() {
    this.route.queryParamMap.subscribe((params) => {
      const name = params.get('name') ?? '';
      this.creating.set(!name);
      this.name.set(name);
      this.owner.set('');
      if (!name) {
        this.content.set(NEW_SKILL);
        return;
      }
      this.loading.set(true);
      this.service.get(name).subscribe({
        next: (d) => {
          this.content.set(d.content);
          this.owner.set(d.owner ?? '');
          this.loadError.set('');
          this.loading.set(false);
        },
        error: (e) => {
          this.loadError.set(apiError(e, 'Could not load the skill'));
          this.loading.set(false);
        },
      });
    });
  }

  save(): void {
    const name = this.name().trim();
    if (!name) {
      this.toasts.error('A skill needs a name');
      return;
    }
    this.saving.set(true);
    this.service.save(name, this.content()).subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.ok(`Saved “${name}”`);
        this.router.navigate([SKILL_VIEW], { queryParams: { name } });
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error(apiError(e, 'Could not save the skill'));
      },
    });
  }

  /**
   * A picked folder. The user names the skill, so the folder's own name is dropped from every
   * path — what matters is that SKILL.md lands at the root of the skill.
   */
  uploadFolder(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length) {
      return;
    }
    const name = (this.name().trim() || folderOf(files[0])).trim();
    if (!name) {
      this.toasts.error('A skill needs a name');
      return;
    }
    this.saving.set(true);
    this.service.upload(name, files).subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.ok(`Uploaded “${name}” (${files.length} files)`);
        this.router.navigate([SKILL_VIEW], { queryParams: { name } });
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error(apiError(e, 'Could not upload the folder'));
      },
    });
  }
}

/** The skeleton a new skill opens with: the frontmatter Claude matches on, and nothing else. */
const NEW_SKILL = ['---', 'description: ', '---', '', ''].join('\n');

/** The picked folder's own name, used when the user did not type one. */
function folderOf(file: File): string {
  const full = (file as File & { webkitRelativePath?: string }).webkitRelativePath ?? '';
  return full.includes('/') ? full.slice(0, full.indexOf('/')) : '';
}
