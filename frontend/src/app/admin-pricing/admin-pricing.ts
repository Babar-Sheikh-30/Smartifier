import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth.service';

interface Pricing {
  priceId: string;
  revision: number;
  amount: number | null;
  currency: string;
  interval: string;
  live: boolean;
  configured: boolean;
  editable: boolean;
}
interface Candidate {
  id: string;
  subscriptionId: string;
  oldAmount: number;
  currency: string;
  interval: string;
}
interface Preview {
  target: Pricing;
  candidates: Candidate[];
  skipped: number;
  more: boolean;
  nextCursor: string | null;
}

@Component({
  selector: 'bs-admin-pricing',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './admin-pricing.html',
  styleUrl: './admin-pricing.scss'
})
export class AdminPricingComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly base = '/api/admin/membership-pricing';
  readonly pricing = signal<Pricing | null>(null);
  readonly preview = signal<Preview | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly reviewSave = signal(false);
  readonly results = signal<Record<string, string>>({});
  readonly currencies = ['cad', 'usd', 'eur', 'gbp', 'aud'];
  hasResults() {
    return Object.keys(this.results()).length > 0;
  }
  amount = '';
  currency = 'cad';
  interval = 'month';
  scope = 'new';
  confirmed = false;
  ngOnInit() {
    void this.load();
  }
  label(amount: number | null, currency: string, interval: string) {
    const money =
      amount === null
        ? 'Custom price'
        : new Intl.NumberFormat('en-CA', {
            style: 'currency',
            currency
          }).format(amount / 100);
    return `${money} ${currency.toUpperCase()}${interval === 'payment' ? ' one-time' : ` / ${interval}`}`;
  }
  minorUnits() {
    return Math.round(Number(this.amount) * 100);
  }
  valid() {
    return (
      /^\d+(\.\d{1,2})?$/.test(this.amount) &&
      this.minorUnits() > 0 &&
      this.minorUnits() <= 99999999
    );
  }
  private async post<T>(path: string, body: object) {
    return firstValueFrom(
      this.http.post<T>(this.base + path, body, { headers: await this.auth.headers() })
    );
  }
  private report(error: unknown) {
    this.error.set(
      error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
        ? error.error.message
        : 'Could not complete this request. Reload pricing and try again.'
    );
  }
  private setPricing(price: Pricing) {
    this.pricing.set(price);
    this.amount = price.amount === null ? '' : (price.amount / 100).toFixed(2);
    this.currency = price.currency;
    this.interval = price.interval === 'year' ? 'year' : 'month';
  }
  async load() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.reviewSave.set(false);
    this.preview.set(null);
    try {
      this.setPricing(await firstValueFrom(this.http.get<Pricing>(this.base)));
    } catch (error) {
      this.report(error);
    } finally {
      this.busy.set(false);
    }
  }
  review() {
    if (this.busy() || !this.valid()) return;
    this.preview.set(null);
    this.reviewSave.set(true);
    this.error.set('');
    this.notice.set('');
  }
  async save() {
    const current = this.pricing();
    if (this.busy() || !this.reviewSave() || !current || !this.valid()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      this.setPricing(
        await this.post<Pricing>('', {
          amount: this.minorUnits(),
          currency: this.currency,
          interval: this.interval,
          expectedRevision: current.revision
        })
      );
      this.reviewSave.set(false);
      this.notice.set('New membership pricing saved. Existing subscriptions have not changed.');
      if (this.scope === 'both') await this.fetchPreview();
    } catch (error) {
      this.report(error);
    } finally {
      this.busy.set(false);
    }
  }
  private async fetchPreview(cursor: string | null = null) {
    this.preview.set(null);
    this.confirmed = false;
    this.results.set({});
    this.preview.set(
      await this.post<Preview>('/preview', { priceId: this.pricing()!.priceId, cursor })
    );
  }
  async reviewExisting(cursor: string | null = null) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.reviewSave.set(false);
    try {
      await this.fetchPreview(cursor);
    } catch (error) {
      this.report(error);
    } finally {
      this.busy.set(false);
    }
  }
  async apply() {
    const preview = this.preview();
    if (this.busy() || !preview || !this.confirmed || Object.keys(this.results()).length) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      for (const candidate of preview.candidates) {
        const result = await this.post<{ status: string; message: string }>(
          `/apply/${candidate.id}`,
          {}
        );
        this.results.update((results) => ({ ...results, [candidate.id]: result.message }));
      }
      this.notice.set(
        'Review complete. Check each result below. Review again for remaining subscriptions.'
      );
    } catch (error) {
      this.report(error);
      this.notice.set(
        'Processing stopped. Some changes may already have completed. Review again to check current subscriptions.'
      );
    } finally {
      this.busy.set(false);
      this.confirmed = false;
    }
  }
}
