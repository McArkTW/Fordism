import { NgTemplateOutlet } from '@angular/common';
import { Component, ViewEncapsulation, inject, signal } from '@angular/core';
import { MatIcon } from '@angular/material/icon';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { marked } from 'marked';
import { errorMessage } from '@/app/core/api-error';
import {
  SkillDetail,
  SkillSource,
  SkillSummary,
  SkillsService,
} from '../data/skills.service';

type TreeNode = {
  name: string;
  path: string;
  isFile: boolean;
  children: TreeNode[];
};

/**
 * Skills — a read-only browse of the tds-skills library (synced, pinned). List every skill,
 * read its SKILL.md rendered, browse its files as a tree, and disable a skill to keep it out
 * of future runs. Editing happens upstream in the tds-skills repo, not here.
 */
@Component({
  selector: 'skills',
  imports: [MatIcon, MatSlideToggle, NgTemplateOutlet],
  host: { class: 'block' },
  encapsulation: ViewEncapsulation.None,
  styleUrls: ['./skills.css'],
  templateUrl: './skills.html',
})
export default class Skills {
  private service = inject(SkillsService);

  skills = signal<SkillSummary[]>([]);
  selected = signal<string | null>(null);
  detail = signal<SkillDetail | null>(null);
  rendered = signal<string>('');
  tree = signal<TreeNode[]>([]);
  raw = signal<boolean>(false);
  source = signal<SkillSource>({});
  error = signal<string>('');

  constructor() {
    this.reload();
    this.service.source().subscribe({
      next: (s) => this.source.set(s ?? {}),
      error: () => {
        /* the source strip just stays blank */
      },
    });
  }

  repoUrl(): string {
    const repo = this.source().repo ?? '';
    return repo.startsWith('http') ? repo : `https://github.com/${repo}`;
  }

  repoLabel(): string {
    const repo = this.source().repo ?? '';
    return (
      repo.replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '') ||
      repo
    );
  }

  reload(): void {
    this.service.list().subscribe({
      next: (list) => this.skills.set(list),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  select(name: string): void {
    this.error.set('');
    this.service.get(name).subscribe({
      next: (d) => {
        this.selected.set(d.name);
        this.detail.set(d);
        this.rendered.set(this.renderMd(d.content));
        this.tree.set(this.buildTree(d.files ?? []));
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  toggle(skill: SkillSummary, enabled: boolean): void {
    this.service.setEnabled(skill.name, enabled).subscribe({
      next: () => {
        this.skills.update((list) =>
          list.map((s) => (s.name === skill.name ? { ...s, enabled } : s))
        );
        const d = this.detail();
        if (d && d.name === skill.name) {
          this.detail.set({ ...d, enabled });
        }
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  /** Strip the YAML frontmatter, render the body to HTML. */
  private renderMd(content: string): string {
    const body = content.replace(/^\uFEFF?---\r?\n[\s\S]*?\r?\n---\r?\n?/, '');
    return marked.parse(body, { async: false }) as string;
  }

  /** Build a directory tree from a flat list of relative file paths. */
  private buildTree(files: string[]): TreeNode[] {
    const root: TreeNode = { name: '', path: '', isFile: false, children: [] };
    for (const file of [...files].sort()) {
      const parts = file.split('/');
      let node = root;
      parts.forEach((part, i) => {
        const isFile = i === parts.length - 1;
        const path = parts.slice(0, i + 1).join('/');
        let child = node.children.find(
          (c) => c.name === part && c.isFile === isFile
        );
        if (!child) {
          child = { name: part, path, isFile, children: [] };
          node.children.push(child);
        }
        node = child;
      });
    }
    const sort = (nodes: TreeNode[]): TreeNode[] => {
      nodes.sort((a, b) =>
        a.isFile === b.isFile ? a.name.localeCompare(b.name) : a.isFile ? 1 : -1
      );
      nodes.forEach((n) => sort(n.children));
      return nodes;
    };
    return sort(root.children);
  }
}
