import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService, Profile } from '../auth.service';
import { MemberStacksComponent } from './member-stacks';
import { PersonalStack, StackService } from './stack.service';

describe('Personal stack removal and approval cancellation', () => {
  let fixture: ComponentFixture<MemberStacksComponent>;
  let stack: PersonalStack;
  let api: {
    defaults: ReturnType<typeof vi.fn>;
    personal: ReturnType<typeof vi.fn>;
    cancelSubmission: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    save: ReturnType<typeof vi.fn>;
  };
  let user: ReturnType<typeof signal<Profile | null>>;

  beforeEach(async () => {
    stack = {
      id: 'personal-1',
      revision: 5,
      sourceStackId: 'default-1',
      sourceVersion: 1,
      versions: [
        {
          number: 1,
          name: 'My stack',
          description: '',
          items: [],
          status: 'PENDING_REVIEW',
          createdAt: '2026-01-01T00:00:00Z',
          reviewNote: null
        }
      ]
    };
    user = signal<Profile | null>({
      id: 'owner',
      name: 'Member',
      email: 'member@example.com',
      role: 'MEMBER',
      createdAt: '',
      phoneNumber: '+14165551234',
      subscriptions: ['personal-1', 'default-1'],
      premium: true
    });
    api = {
      defaults: vi.fn().mockResolvedValue([]),
      personal: vi.fn().mockResolvedValue([stack]),
      cancelSubmission: vi.fn(),
      remove: vi.fn().mockResolvedValue(undefined),
      create: vi.fn(),
      save: vi.fn()
    };
    await TestBed.configureTestingModule({
      imports: [MemberStacksComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AuthService, useValue: { user } },
        { provide: StackService, useValue: api }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(MemberStacksComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => vi.restoreAllMocks());

  function button(text: string): HTMLButtonElement {
    const match = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>
    ).find((button) => button.textContent?.trim() === text);
    if (!match) throw new Error(`Button not found: ${text}`);
    return match;
  }

  async function click(text: string) {
    button(text).click();
    await vi.waitFor(() => expect(fixture.componentInstance.busy()).toBe(false));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('requires confirmation and lets the member keep the stack', async () => {
    await click('Remove stack');
    expect(fixture.nativeElement.textContent).toContain('Remove My stack?');
    expect(api.remove).not.toHaveBeenCalled();
    await click('Keep stack');
    expect(fixture.componentInstance.removing()).toBeNull();
    expect(fixture.componentInstance.personal()).toEqual([stack]);
    expect(api.remove).not.toHaveBeenCalled();
  });

  it('removes only the selected stack and its local subscription after confirmation', async () => {
    await click('Remove stack');
    await click('Remove stack');
    expect(api.remove).toHaveBeenCalledWith(stack);
    expect(fixture.componentInstance.personal()).toEqual([]);
    expect(user()?.subscriptions).toEqual(['default-1']);
    expect(fixture.componentInstance.removing()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Personal stack removed');
  });

  it('keeps the stack and subscription when removal fails', async () => {
    api.remove.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: { message: 'This stack has changed. Reload your stacks.' }
      })
    );
    await click('Remove stack');
    await click('Remove stack');
    expect(fixture.componentInstance.personal()).toEqual([stack]);
    expect(user()?.subscriptions).toContain(stack.id);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Reload your stacks'
    );
    expect(fixture.componentInstance.busy()).toBe(false);
    // Closing the confirmation allows the member to reload the new revision.
    await click('Keep stack');
    await click('Reload stacks');
    expect(api.personal).toHaveBeenCalledTimes(2);
  });

  it('cancels the latest approval request and shows the returned draft', async () => {
    const draft = {
      ...stack,
      revision: 6,
      versions: [{ ...stack.versions[0], status: 'DRAFT' as const }]
    };
    api.cancelSubmission.mockResolvedValue(draft);
    await click('Cancel approval request');
    expect(api.cancelSubmission).toHaveBeenCalledWith(stack, 1);
    expect(fixture.componentInstance.personal()).toEqual([draft]);
    expect(button('Submit for approval')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Approval request for version 1 cancelled');
  });

  it('offers cancellation for older pending versions without replacing newer drafts', async () => {
    const newer = { ...stack.versions[0], number: 2, status: 'DRAFT' as const };
    stack = { ...stack, versions: [...stack.versions, newer] };
    fixture.componentInstance.personal.set([stack]);
    fixture.detectChanges();
    const result = {
      ...stack,
      revision: 6,
      versions: [{ ...stack.versions[0], status: 'DRAFT' as const }, newer]
    };
    api.cancelSubmission.mockResolvedValue(result);
    await click('Cancel approval request for version 1');
    expect(api.cancelSubmission).toHaveBeenCalledWith(stack, 1);
    expect(fixture.componentInstance.latest(fixture.componentInstance.personal()[0])).toEqual(
      newer
    );
  });

  it('preserves pending status and displays errors if an admin already reviewed the request', async () => {
    api.cancelSubmission.mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: { message: 'This request is no longer awaiting approval. Reload your stacks.' }
      })
    );
    await click('Cancel approval request');
    expect(fixture.componentInstance.personal()).toEqual([stack]);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'no longer awaiting approval'
    );
    expect(button('Cancel approval request').disabled).toBe(false);
  });

  it('prevents duplicate removal requests while a removal is in flight', async () => {
    let finish!: () => void;
    api.remove.mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve;
      })
    );
    await click('Remove stack');
    const pending = fixture.componentInstance.remove();
    fixture.detectChanges();
    expect(button('Remove stack').disabled).toBe(true);
    await fixture.componentInstance.remove();
    fixture.componentInstance.cancelRemoval();
    expect(fixture.componentInstance.removing()).toEqual(stack);
    expect(api.remove).toHaveBeenCalledTimes(1);
    finish();
    await pending;
  });

  it('hides premium stack controls from free members', async () => {
    user.update((profile) => (profile ? { ...profile, premium: false } : null));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Remove stack');
    expect(fixture.nativeElement.textContent).not.toContain('Cancel approval request');
  });

  it.each([
    ['', 'Useful facts', 'Stack name is required.'],
    ['   ', 'Useful facts', 'Stack name is required.'],
    ['My stack', '', 'Description is required.'],
    ['My stack', ' \n\t ', 'Description is required.'],
    ['', '', 'Stack name is required. Description is required.']
  ])(
    'explains why saving name %j and description %j is blocked',
    async (name, description, message) => {
      // Avoid browser layout APIs in jsdom; these tests exercise the form and its visible errors.
      vi.spyOn(window, 'requestAnimationFrame').mockImplementation(() => 0);
      await click('Create a stack');
      const component = fixture.componentInstance;
      component.name = name;
      component.description = description;
      component.items[0].body = 'A fact';
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      await fixture.whenStable();
      // The submit handler must reject blank metadata even if called directly with a valid flag.
      await component.save(true);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('#stack-editor-error').textContent).toContain(
        message
      );
      expect(component.editorOpen()).toBe(true);
      expect(component.busy()).toBe(false);
      expect(api.create).not.toHaveBeenCalled();
      expect(api.save).not.toHaveBeenCalled();
    }
  );
});
