import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../auth.service';
import {
  ContentItem,
  DefaultStack,
  PersonalStack,
  StackService,
  StackVersion
} from './stack.service';

@Component({
  selector: 'bs-member-stacks',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './member-stacks.html',
  styleUrl: './member-stacks.scss'
})
export class MemberStacksComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(StackService);
  readonly defaults = signal<DefaultStack[]>([]);
  readonly personal = signal<PersonalStack[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly editorOpen = signal(false);
  readonly editing = signal<PersonalStack | null>(null);
  readonly types = ['FACT', 'QUOTE', 'JOKE', 'VOCABULARY'] as const;
  name = '';
  description = '';
  items: ContentItem[] = [];
  readonly statusLabels = {
    DRAFT: 'Draft',
    PENDING_REVIEW: 'Awaiting admin approval',
    APPROVED: 'Approved',
    REJECTED: 'Changes requested'
  };

  ngOnInit() {
    void this.load();
  }

  async load() {
    if (this.busy() || this.editorOpen()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const [defaults, personal] = await Promise.all([
        this.api.defaults(),
        this.auth.user()?.premium ? this.api.personal() : Promise.resolve([])
      ]);
      this.defaults.set(defaults);
      this.personal.set(personal);
    } catch (error) {
      this.report(error);
    } finally {
      this.loading.set(false);
    }
  }

  latest(stack: PersonalStack) {
    return stack.versions[stack.versions.length - 1];
  }

  subscribed(id: string) {
    return this.auth.user()?.subscriptions.includes(id) ?? false;
  }

  private report(error: unknown) {
    this.error.set(
      error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
        ? error.error.message
        : 'Could not update your stacks. Please try again.'
    );
  }

  private async action(work: () => Promise<void>) {
    if (this.busy() || this.loading()) return;
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

  async toggle(id: string) {
    await this.action(async () => {
      const subscribe = !this.subscribed(id);
      await this.auth.subscribe(id, subscribe);
      this.notice.set(
        subscribe ? 'Subscription saved. Text delivery is coming soon.' : 'Unsubscribed.'
      );
    });
  }

  start(stack: PersonalStack | null = null, version?: StackVersion) {
    if (this.busy() || this.editorOpen()) return;
    this.populate(stack, version);
  }

  private populate(stack: PersonalStack | null, version?: StackVersion) {
    const content = version ?? (stack ? this.latest(stack) : null);
    this.editing.set(stack);
    this.name = content?.name ?? '';
    this.description = content?.description ?? '';
    this.items = content ? content.items.map((item) => ({ ...item })) : [this.emptyItem()];
    this.editorOpen.set(true);
    this.error.set('');
  }

  emptyItem(): ContentItem {
    return { type: 'FACT', body: '', answer: '', attribution: '' };
  }

  addItem() {
    if (this.items.length < 50 && !this.busy()) this.items.push(this.emptyItem());
  }

  removeItem(index: number) {
    if (this.items.length > 1 && !this.busy()) this.items.splice(index, 1);
  }

  cancel() {
    if (!this.busy()) this.editorOpen.set(false);
  }

  private replace(stack: PersonalStack) {
    this.personal.update((stacks) => [
      stack,
      ...stacks.filter((existing) => existing.id !== stack.id)
    ]);
  }

  async copy(id: string) {
    await this.action(async () => {
      const stack = await this.api.copy(id);
      this.replace(stack);
      this.populate(stack);
      this.notice.set('Personal copy created. The original default stack is unchanged.');
    });
  }

  async save() {
    if (!this.name.trim() || this.items.some((item) => !item.body.trim())) return;
    await this.action(async () => {
      const stack = this.editing();
      const edit = {
        name: this.name.trim(),
        description: this.description.trim(),
        items: this.items.map((item) => ({ ...item, body: item.body.trim() })),
        expectedRevision: stack?.revision ?? 0
      };
      this.replace(await (stack ? this.api.save(stack, edit) : this.api.create(edit)));
      this.editorOpen.set(false);
      this.notice.set('Draft saved. Submit it when you are ready for admin approval.');
    });
  }

  async submit(stack: PersonalStack) {
    await this.action(async () => {
      this.replace(await this.api.submit(stack));
      this.notice.set('Submitted for admin approval. This version is now read-only.');
    });
  }
}
