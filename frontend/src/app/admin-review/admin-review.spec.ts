import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminReviewComponent } from './admin-review';
import { AdminReviewService, ReviewEntry } from './admin-review.service';

describe('Admin review inbox', () => {
  let fixture: ComponentFixture<AdminReviewComponent>;
  let component: AdminReviewComponent;
  let api: { pending: ReturnType<typeof vi.fn>; decide: ReturnType<typeof vi.fn> };
  const entry: ReviewEntry = {
    stackId: 'personal',
    revision: 3,
    memberName: 'Premium Member',
    memberEmail: 'member@example.test',
    version: {
      number: 1,
      name: 'Submitted science',
      description: 'For review',
      items: [{ type: 'FACT', body: 'The submitted content', answer: '', attribution: 'A source' }],
      status: 'PENDING_REVIEW',
      createdAt: '2026-11-03T10:00:00Z',
      submittedAt: '2026-11-03T11:00:00Z',
      reviewNote: null
    }
  };
  beforeEach(async () => {
    api = {
      pending: vi.fn().mockResolvedValue([entry]),
      decide: vi.fn().mockResolvedValue(undefined)
    };
    await TestBed.configureTestingModule({
      imports: [AdminReviewComponent],
      providers: [provideZonelessChangeDetection(), { provide: AdminReviewService, useValue: api }]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminReviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('shows the submitted snapshot, member, premium label and approve/deny actions', () => {
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Review inbox');
    expect(text).toContain('Premium submissions');
    expect(text).toContain('Submitted science');
    expect(text).toContain('The submitted content');
    expect(text).toContain('Approve stack');
    expect(text).toContain('Deny');
    expect(text).not.toContain('Untitled');
  });

  it('approves the exact entry and refreshes remaining revision counters', async () => {
    component.notes[component.key(entry)] = 'Great work';
    api.pending.mockResolvedValue([]);
    await component.decide(entry, true);
    expect(api.decide).toHaveBeenCalledWith(entry, true, 'Great work');
    expect(component.entries()).toEqual([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('All caught up.');
    expect(component.notice()).toContain('approved');
    expect(api.pending).toHaveBeenCalledTimes(2);
  });

  it('denies with feedback and leaves the queue intact when the request fails', async () => {
    component.notes[component.key(entry)] = 'Please verify the source';
    api.decide.mockRejectedValue(
      new HttpErrorResponse({ status: 409, error: { message: 'Refresh the queue.' } })
    );
    await component.decide(entry, false);
    expect(api.decide).toHaveBeenCalledWith(entry, false, 'Please verify the source');
    expect(component.entries()).toHaveLength(1);
    expect(component.notes[component.key(entry)]).toBe('Please verify the source');
    expect(component.error()).toBe('Refresh the queue.');
    expect(component.busy()).toBeNull();
  });

  it('blocks duplicate decisions while a request is pending', async () => {
    let resolve!: () => void;
    api.decide.mockReturnValue(
      new Promise<void>((done) => {
        resolve = done;
      })
    );
    const pending = component.decide(entry, true);
    await component.decide(entry, false);
    expect(api.decide).toHaveBeenCalledTimes(1);
    resolve();
    await pending;
  });

  it('shows a retryable load error and rejects overlong notes', async () => {
    api.pending.mockRejectedValue(new Error('Offline'));
    await component.load();
    expect(component.error()).toContain('Please try again');
    component.notes[component.key(entry)] = 'x'.repeat(501);
    await component.decide(entry, false);
    expect(api.decide).not.toHaveBeenCalled();
    expect(component.error()).toContain('500');
  });

  it('offers standard denial reasons and sends the reason with the explanation', async () => {
    const key = component.key(entry);
    expect(fixture.nativeElement.textContent).toContain('Inappropriate or offensive content');
    component.reasons[key] = 'Inaccurate or misleading information';
    component.notes[key] = 'Please verify the dates.';
    api.pending.mockResolvedValue([]);
    await component.decide(entry, false);
    expect(api.decide).toHaveBeenCalledWith(
      entry,
      false,
      'Inaccurate or misleading information: Please verify the dates.'
    );
    expect(component.reasons[key]).toBeUndefined();
  });

  it('requires a denial reason and requires an explanation for Other', async () => {
    await component.decide(entry, false);
    expect(api.decide).not.toHaveBeenCalled();
    component.reasons[component.key(entry)] = 'Other';
    await component.decide(entry, false);
    expect(api.decide).not.toHaveBeenCalled();
    component.notes[component.key(entry)] = 'Please clarify the learning objective.';
    await component.decide(entry, false);
    expect(api.decide).toHaveBeenCalledWith(entry, false, 'Please clarify the learning objective.');
  });

  it('checks the combined reason and explanation limit without applying the reason to approval', async () => {
    component.reasons[component.key(entry)] = 'Duplicate content';
    component.notes[component.key(entry)] = 'x'.repeat(490);
    await component.decide(entry, false);
    expect(api.decide).not.toHaveBeenCalled();
    expect(component.error()).toContain('500');
    component.notes[component.key(entry)] = 'Approved with feedback';
    await component.decide(entry, true);
    expect(api.decide).toHaveBeenCalledWith(entry, true, 'Approved with feedback');
  });
});
