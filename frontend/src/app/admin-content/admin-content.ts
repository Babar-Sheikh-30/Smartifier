import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminContentService, AdminItem, AdminStack } from './admin-content.service';
import { ContentItem } from '../member-stacks/stack.service';

@Component({
  selector: 'bs-admin-content',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './admin-content.html',
  styleUrl: './admin-content.scss'
})
export class AdminContentComponent implements OnInit {
  private readonly api = inject(AdminContentService);
  readonly stacks = signal<AdminStack[]>([]);
  readonly selected = signal<AdminStack | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly stackEditor = signal(false);
  readonly itemEditor = signal(false);
  readonly confirmation = signal<{ item: AdminItem | null } | null>(null);
  readonly types = ['FACT', 'QUOTE', 'JOKE', 'VOCABULARY'] as const;
  stackName = '';
  stackDescription = '';
  itemId: string | null = null;
  item: ContentItem = this.emptyItem();

  ngOnInit() {
    void this.load();
  }
  latest(stack: AdminStack) {
    return stack.versions[stack.versions.length - 1];
  }
  items(stack: AdminStack) {
    return this.latest(stack).items as AdminItem[];
  }
  published(stack: AdminStack) {
    return stack.versions.find((v) => v.number === stack.publishedVersion)?.items ?? [];
  }
  status(stack: AdminStack, item: AdminItem) {
    const live = this.published(stack).find((i) => i.id === item.id);
    if (!live) return 'Draft';
    return ['title', 'description', 'type', 'body', 'answer', 'attribution'].every(
      (key) => (live[key as keyof ContentItem] ?? '') === (item[key as keyof ContentItem] ?? '')
    )
      ? 'Published'
      : 'Unpublished changes';
  }
  metadataPending(stack: AdminStack) {
    const live = stack.versions.find((v) => v.number === stack.publishedVersion);
    return (
      live &&
      (live.name !== this.latest(stack).name || live.description !== this.latest(stack).description)
    );
  }
  locked() {
    return this.busy() || this.loading();
  }
  editing() {
    return this.stackEditor() || this.itemEditor();
  }

  private report(error: unknown) {
    this.error.set(
      error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
        ? error.error.message
        : 'Could not complete the request. Please try again.'
    );
  }
  async load() {
    if (this.locked() || this.editing() || this.confirmation()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const stacks = await this.api.list();
      this.stacks.set(stacks);
      this.selected.set(stacks.find((s) => s.id === this.selected()?.id) ?? null);
    } catch (error) {
      this.report(error);
    } finally {
      this.loading.set(false);
    }
  }
  select(id: string) {
    if (this.locked() || this.editing() || this.confirmation()) return;
    this.selected.set(this.stacks().find((s) => s.id === id) ?? null);
    this.error.set('');
    this.notice.set('');
  }
  startStack(create = false) {
    if (this.locked() || this.editing() || this.confirmation()) return;
    if (create) this.selected.set(null);
    const stack = this.selected();
    this.stackName = stack ? this.latest(stack).name : '';
    this.stackDescription = stack ? this.latest(stack).description : '';
    this.stackEditor.set(true);
    this.error.set('');
  }
  startItem(item?: AdminItem) {
    if (this.locked() || this.editing() || this.confirmation()) return;
    this.itemId = item?.id ?? null;
    this.item = item
      ? { ...item, title: item.title ?? '', description: item.description ?? '' }
      : this.emptyItem();
    this.itemEditor.set(true);
    this.error.set('');
  }
  private emptyItem(): ContentItem {
    return { title: '', description: '', type: 'FACT', body: '', answer: '', attribution: '' };
  }
  cancel() {
    if (this.locked()) return;
    this.stackEditor.set(false);
    this.itemEditor.set(false);
    this.error.set('');
  }
  private replace(stack: AdminStack) {
    this.stacks.update((stacks) => [stack, ...stacks.filter((s) => s.id !== stack.id)]);
    this.selected.set(stack);
  }
  private async action(work: () => Promise<void>) {
    if (this.locked()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      await work();
    } catch (error) {
      this.report(error);
    } finally {
      this.busy.set(false);
    }
  }
  async saveStack(valid: boolean) {
    if (!valid || !this.stackName.trim()) {
      this.error.set('Enter a stack name and check the field limits.');
      return;
    }
    await this.action(async () => {
      const stack = this.selected();
      const saved = stack
        ? await this.api.change(stack, 'versions', {
            name: this.stackName.trim(),
            description: this.stackDescription.trim()
          })
        : await this.api.create(this.stackName.trim(), this.stackDescription.trim());
      this.replace(saved);
      this.stackEditor.set(false);
      this.notice.set(
        'Stack saved. Publish content or stack details when ready to make changes available to members.'
      );
    });
  }
  async saveItem(valid: boolean) {
    if (!valid || !this.item.title?.trim() || !this.item.body.trim()) {
      this.error.set('Enter a content title and body, and check the field limits.');
      return;
    }
    const stack = this.selected();
    if (!stack) return;
    await this.action(async () => {
      const path = this.itemId ? `content/${encodeURIComponent(this.itemId)}/versions` : 'content';
      this.replace(await this.api.change(stack, path, this.item));
      this.itemEditor.set(false);
      this.notice.set('Content saved as a draft. Publish it when ready.');
    });
  }
  async publish(item?: AdminItem) {
    const stack = this.selected();
    if (!stack || this.editing()) return;
    await this.action(async () => {
      this.replace(
        await this.api.change(
          stack,
          item ? `content/${encodeURIComponent(item.id)}/publish` : 'publish'
        )
      );
      this.notice.set(
        item
          ? 'Selected content published. Other draft content remains unpublished.'
          : 'Stack details published.'
      );
    });
  }
  requestRemoval(item: AdminItem | null = null) {
    if (!this.locked() && !this.editing()) this.confirmation.set({ item });
  }
  cancelRemoval() {
    if (!this.locked()) this.confirmation.set(null);
  }
  async remove() {
    const stack = this.selected();
    const confirmation = this.confirmation();
    if (!stack || !confirmation) return;
    await this.action(async () => {
      if (confirmation.item) {
        this.replace(
          await this.api.change(stack, `content/${encodeURIComponent(confirmation.item.id)}/remove`)
        );
        this.notice.set('Content removed from the stack and its published content.');
      } else {
        await this.api.change(stack, 'remove');
        this.stacks.update((stacks) => stacks.filter((s) => s.id !== stack.id));
        this.selected.set(null);
        this.notice.set(
          'Stack removed from availability. History and personal copies are preserved.'
        );
      }
      this.confirmation.set(null);
    });
  }
}
