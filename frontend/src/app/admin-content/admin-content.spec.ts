import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminContentComponent } from './admin-content';
import { AdminContentService, AdminStack } from './admin-content.service';

describe('Admin content management', () => {
  let fixture: ComponentFixture<AdminContentComponent>;
  let component: AdminContentComponent;
  let api: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    change: ReturnType<typeof vi.fn>;
  };
  const stack: AdminStack = {
    id: 'shared',
    revision: 4,
    publishedVersion: 1,
    versions: [
      {
        number: 1,
        name: 'Science',
        description: '',
        status: 'APPROVED',
        createdAt: '',
        reviewNote: null,
        items: [
          {
            id: 'first',
            title: 'First',
            type: 'FACT',
            body: 'Original',
            answer: '',
            attribution: ''
          }
        ]
      },
      {
        number: 2,
        name: 'Science',
        description: '',
        status: 'DRAFT',
        createdAt: '',
        reviewNote: null,
        items: [
          {
            id: 'first',
            title: 'First',
            type: 'FACT',
            body: 'Edited',
            answer: '',
            attribution: ''
          },
          {
            id: 'second',
            title: 'Second',
            type: 'FACT',
            body: 'Draft',
            answer: '',
            attribution: ''
          }
        ]
      }
    ]
  };

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([stack]),
      create: vi.fn(),
      change: vi.fn().mockResolvedValue(stack)
    };
    await TestBed.configureTestingModule({
      imports: [AdminContentComponent],
      providers: [provideZonelessChangeDetection(), { provide: AdminContentService, useValue: api }]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminContentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    component.select('shared');
    fixture.detectChanges();
  });

  it('shows working content and distinguishes drafts from unpublished edits', () => {
    expect(fixture.nativeElement.textContent).toContain('Stack Management');
    expect(fixture.nativeElement.textContent).toContain('Content Management');
    expect(component.status(stack, component.items(stack)[0])).toBe('Unpublished changes');
    expect(component.status(stack, component.items(stack)[1])).toBe('Draft');
  });

  it('cancels deletion without sending a mutation', async () => {
    component.requestRemoval();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).not.toBeNull();
    component.cancelRemoval();
    await component.remove();
    expect(api.change).not.toHaveBeenCalled();
    expect(component.stacks()).toHaveLength(1);
  });

  it('places validation inside the content editor, focuses it and scrolls it into view', async () => {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
    const scroll = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scroll
    });
    try {
      component.startItem();
      fixture.detectChanges();
      await component.saveItem(false);
      fixture.detectChanges();
      await fixture.whenStable();
      const error = fixture.nativeElement.querySelector('#admin-content-editor-error');
      expect(error).not.toBeNull();
      expect(error.closest('form')).not.toBeNull();
      expect(document.activeElement).toBe(error);
      expect(scroll).toHaveBeenCalledWith({ block: 'center', behavior: 'auto' });
      expect(fixture.nativeElement.querySelectorAll('[role="alert"]')).toHaveLength(1);
      expect(api.change).not.toHaveBeenCalled();
    } finally {
      if (original) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original);
      else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
    }
  });

  it('edits body-only content without title or description fields or fallback headings', async () => {
    expect(fixture.nativeElement.textContent).not.toContain('Untitled');
    component.startItem();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#admin-content-title-input')).toBeNull();
    expect(fixture.nativeElement.querySelector('#admin-content-description')).toBeNull();
    component.item.body = 'A concise fact';
    await component.saveItem(true);
    expect(api.change).toHaveBeenCalledWith(stack, 'content', {
      type: 'FACT',
      body: 'A concise fact',
      answer: '',
      attribution: ''
    });
  });

  it('removes a stack only after confirmation and a successful response', async () => {
    component.requestRemoval();
    await component.remove();
    expect(api.change).toHaveBeenCalledWith(stack, 'remove');
    expect(component.stacks()).toHaveLength(0);
    expect(component.selected()).toBeNull();
    expect(component.confirmation()).toBeNull();
  });

  it('preserves the editor and entered content after a conflicting save', async () => {
    api.change.mockRejectedValue(
      new HttpErrorResponse({ status: 409, error: { message: 'Reload before saving.' } })
    );
    component.startItem(component.items(stack)[0]);
    component.item.body = 'Keep my changes';
    await component.saveItem(true);
    expect(component.itemEditor()).toBe(true);
    expect(component.item.body).toBe('Keep my changes');
    expect(component.error()).toBe('Reload before saving.');
    expect(component.busy()).toBe(false);
  });

  it('publishes only the selected saved item and blocks duplicate requests', async () => {
    let resolve!: (value: AdminStack) => void;
    api.change.mockReturnValue(
      new Promise<AdminStack>((done) => {
        resolve = done;
      })
    );
    const pending = component.publish(component.items(stack)[1]);
    await component.publish(component.items(stack)[1]);
    expect(api.change).toHaveBeenCalledTimes(1);
    expect(api.change).toHaveBeenCalledWith(stack, 'content/second/publish');
    resolve(stack);
    await pending;
  });

  it('blocks invalid content without sending it and reports failed deletion', async () => {
    component.startItem();
    await component.saveItem(false);
    expect(api.change).not.toHaveBeenCalled();
    component.cancel();
    api.change.mockRejectedValue(
      new HttpErrorResponse({ status: 503, error: { message: 'Database unavailable.' } })
    );
    component.requestRemoval();
    await component.remove();
    expect(component.stacks()).toHaveLength(1);
    expect(component.confirmation()).not.toBeNull();
    expect(component.error()).toBe('Database unavailable.');
  });
});
