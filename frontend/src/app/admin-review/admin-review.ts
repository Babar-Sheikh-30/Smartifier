import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminReviewService, ReviewEntry } from './admin-review.service';

@Component({
  selector: 'bs-admin-review',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './admin-review.html',
  styleUrl: './admin-review.scss'
})
export class AdminReviewComponent implements OnInit {
  private readonly api = inject(AdminReviewService);
  readonly entries = signal<ReviewEntry[]>([]);
  readonly loading = signal(false);
  readonly busy = signal<string | null>(null);
  readonly error = signal('');
  readonly notice = signal('');
  notes: Record<string, string> = {};
  reasons: Record<string, string> = {};
  readonly denialReasons = [
    'Inappropriate or offensive content',
    'Inaccurate or misleading information',
    'Missing or unreliable sources',
    'Low-quality or incomplete content',
    'Duplicate content',
    'Off-topic content',
    'Other'
  ];
  noteLimit(entry: ReviewEntry) {
    const reason = this.reasons[this.key(entry)];
    return reason && reason !== 'Other' ? 500 - reason.length - 2 : 500;
  }

  ngOnInit() {
    void this.load();
  }
  key(entry: ReviewEntry) {
    return `${entry.stackId}:${entry.version.number}`;
  }
  private report(error: unknown) {
    this.error.set(
      error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
        ? error.error.message
        : 'Could not load or update the review queue. Please try again.'
    );
  }
  private async refresh() {
    this.loading.set(true);
    try {
      this.entries.set(await this.api.pending());
    } finally {
      this.loading.set(false);
    }
  }
  async load() {
    if (this.loading() || this.busy()) return;
    this.error.set('');
    try {
      await this.refresh();
    } catch (error) {
      this.report(error);
    }
  }
  async decide(entry: ReviewEntry, approved: boolean) {
    if (this.loading() || this.busy()) return;
    const key = this.key(entry);
    const details = (this.notes[key] ?? '').trim();
    const reason = this.reasons[key] ?? '';
    const note =
      !approved && reason && reason !== 'Other'
        ? reason + (details ? ': ' + details : '')
        : details;
    this.error.set('');
    this.notice.set('');
    if (!approved && (!note || (reason === 'Other' && !details))) {
      this.error.set(
        'Select a denial reason or write an explanation. For Other, please explain why the stack was denied.'
      );
      return;
    }
    if (note.length > 500) {
      this.error.set('Keep the review note within 500 characters.');
      return;
    }
    this.busy.set(key);
    try {
      await this.api.decide(entry, approved, note);
      this.entries.update((entries) => entries.filter((e) => this.key(e) !== key));
      delete this.notes[key];
      delete this.reasons[key];
      this.notice.set(
        `${entry.version.name}, version ${entry.version.number}, ${approved ? 'approved' : 'denied'}. The member can see your decision in their stack history.`
      );
      // Other submissions for this stack need its updated database revision.
      await this.refresh();
    } catch (error) {
      this.report(error);
    } finally {
      this.busy.set(null);
    }
  }
}
