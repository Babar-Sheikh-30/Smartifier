import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DOCUMENT, DatePipe } from '@angular/common';
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
  private readonly document = inject(DOCUMENT);
  readonly defaults = signal<DefaultStack[]>([]);
  readonly personal = signal<PersonalStack[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly editorOpen = signal(false);
  readonly editing = signal<PersonalStack | null>(null);
  readonly savedStackId = signal<string | null>(null);
  readonly removing = signal<PersonalStack | null>(null);
  readonly types = ['FACT', 'QUOTE', 'JOKE', 'VOCABULARY'] as const;
  name = '';
  description = '';
  items: ContentItem[] = [];
  readonly statusLabels = {
    DRAFT: 'Draft',
    PENDING_REVIEW: 'Awaiting admin approval',
    APPROVED: 'Approved',
    REJECTED: 'Denied'
  };

  ngOnInit() {
    void this.load();
  }

  async load() {
    // Keep a refresh from replacing data while the member is editing or saving.
    if (this.busy() || this.editorOpen() || this.removing()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const [defaults, personal] = await Promise.all([
        // Free members can browse defaults; personal stacks require premium access.
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
    if (this.removing()) return;
    await this.action(async () => {
      const subscribe = !this.subscribed(id);
      await this.auth.subscribe(id, subscribe);
      this.notice.set(
        subscribe ? 'Subscription saved. Text delivery is coming soon.' : 'Unsubscribed.'
      );
    });
  }

  start(stack: PersonalStack | null = null, version?: StackVersion) {
    if (this.busy() || this.editorOpen() || this.removing()) return;
    this.populate(stack, version);
  }

  private populate(stack: PersonalStack | null, version?: StackVersion) {
    const content = version ?? (stack ? this.latest(stack) : null);
    this.editing.set(stack);
    this.name = content?.name ?? '';
    this.description = content?.description ?? '';
    // Edit copies so cancelling cannot change the saved version shown elsewhere.
    this.items = content ? content.items.map((item) => ({ ...item })) : [this.emptyItem()];
    this.editorOpen.set(true);
    this.error.set('');
    this.notice.set('');
    this.focusElement('stack-name');
  }

  private focusElement(id: string) {
    this.document.defaultView?.requestAnimationFrame(() => {
      const element = this.document.getElementById(id);
      element?.focus({ preventScroll: true });
      element?.scrollIntoView({ block: 'center' });
    });
  }

  emptyItem(): ContentItem {
    // Start with a fact; answers and attribution stay empty until the content needs them.
    return { type: 'FACT', body: '', answer: '', attribution: '' };
  }

  addItem() {
    if (this.items.length < 50 && !this.busy()) this.items.push(this.emptyItem());
  }

  removeItem(index: number) {
    if (this.items.length > 1 && !this.busy()) this.items.splice(index, 1);
  }

  cancel() {
    if (!this.busy()) {
      this.editorOpen.set(false);
      this.error.set('');
    }
  }

  private replace(stack: PersonalStack) {
    this.personal.update((stacks) => [
      stack,
      ...stacks.filter((existing) => existing.id !== stack.id)
    ]);
  }

  async copy(id: string) {
    if (this.removing() || this.editorOpen()) return;
    await this.action(async () => {
      const stack = await this.api.copy(id);
      this.replace(stack);
      this.populate(stack);
      this.notice.set('Personal copy created. The original default stack is unchanged.');
    });
  }

  async save(formValid: boolean) {
    if (this.busy()) return;
    const problems: string[] = [];
    if (!this.name.trim()) problems.push('Stack name is required.');
    else if (this.name.length > 100) problems.push('Stack name must be 100 characters or fewer.');
    if (!this.description.trim()) problems.push('Description is required.');
    else if (this.description.length > 500)
      problems.push('Description must be 500 characters or fewer.');
    if (this.items.length === 0 || this.items.some((item) => !item.body.trim())) {
      problems.push(
        'Enter content for every item, or remove unused items. At least one item is required.'
      );
    }
    if (!formValid && problems.length === 0)
      problems.push('Check the content field length limits before saving.');
    if (problems.length) {
      this.error.set('Could not save your stack. ' + problems.join(' '));
      this.focusElement('stack-editor-error');
      return;
    }
    await this.action(async () => {
      const stack = this.editing();
      const edit = {
        name: this.name.trim(),
        description: this.description.trim(),
        items: this.items.map((item) => ({ ...item, body: item.body.trim() })),
        // New stacks have no saved revision; existing stacks must match the loaded revision.
        expectedRevision: stack?.revision ?? 0
      };
      const saved = await (stack ? this.api.save(stack, edit) : this.api.create(edit));
      this.replace(saved);
      this.savedStackId.set(saved.id);
      this.editorOpen.set(false);
      this.notice.set(
        stack
          ? 'Changes saved to your personal stacks.'
          : 'Stack created and added to your personal stacks.'
      );
      this.focusElement('personal-stack-' + saved.id);
    });
  }

  async submit(stack: PersonalStack) {
    if (this.removing() || this.editorOpen()) return;
    await this.action(async () => {
      this.replace(await this.api.submit(stack));
      this.notice.set('Submitted for admin approval. This version is now read-only.');
    });
  }

  async cancelSubmission(stack: PersonalStack, version: StackVersion) {
    if (this.editorOpen() || this.removing()) return;
    await this.action(async () => {
      this.replace(await this.api.cancelSubmission(stack, version.number));
      this.notice.set(
        `Approval request for version ${version.number} cancelled. That version is now a draft.`
      );
    });
  }

  requestRemoval(stack: PersonalStack) {
    if (this.busy() || this.loading() || this.editorOpen() || this.removing()) return;
    this.removing.set(stack);
    this.error.set('');
    this.notice.set('');
  }

  cancelRemoval() {
    if (!this.busy()) this.removing.set(null);
  }

  async remove() {
    const stack = this.removing();
    if (!stack || this.editorOpen()) return;
    await this.action(async () => {
      await this.api.remove(stack);
      this.personal.update((stacks) => stacks.filter((existing) => existing.id !== stack.id));
      this.auth.user.update((user) =>
        user
          ? {
              ...user,
              subscriptions: user.subscriptions.filter((id) => id !== stack.id)
            }
          : null
      );
      if (this.savedStackId() === stack.id) this.savedStackId.set(null);
      this.removing.set(null);
      this.notice.set(
        'Personal stack removed. Its subscriptions and pending approval requests are stopped.'
      );
    });
  }
}
