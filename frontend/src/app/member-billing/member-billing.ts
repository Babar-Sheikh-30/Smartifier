import { HttpErrorResponse } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService, BillingMembership } from '../auth.service';

@Component({
  selector: 'bs-member-billing',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './member-billing.html',
  styleUrl: './member-billing.scss'
})
export class MemberBillingComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly checkout = inject(ActivatedRoute).snapshot.queryParamMap.get('checkout');
  readonly memberships = signal<BillingMembership[] | null>(null);
  readonly loading = signal(false);
  readonly opening = signal<string | null>(null);
  readonly error = signal('');
  readonly checkoutNotice =
    this.checkout === 'returned'
      ? 'Stripe is confirming your payment. Refresh billing in a few seconds if your membership has not appeared.'
      : this.checkout === 'cancelled'
        ? 'Checkout was cancelled. You can try again when you’re ready.'
        : '';

  ngOnInit() {
    void this.load();
  }

  async load() {
    if (this.loading() || this.opening()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      this.memberships.set(await this.auth.getBillingMemberships());
      await this.auth.loadUser();
    } catch (error) {
      this.report(error, 'Could not load your membership billing.');
    } finally {
      this.loading.set(false);
    }
  }

  status(status: string): string {
    return (
      {
        active: 'Active',
        trialing: 'Trial',
        past_due: 'Payment past due',
        unpaid: 'Payment needed',
        paused: 'Paused',
        incomplete: 'Payment pending'
      }[status] ?? 'See Stripe for details'
    );
  }

  async open(id?: string, paymentMethod = false) {
    if (this.opening()) return;
    this.opening.set(paymentMethod ? 'payment-method' : (id ?? 'checkout'));
    this.error.set('');
    try {
      window.location.assign(
        id ? await this.auth.openBillingPortal(id, paymentMethod) : await this.auth.createCheckout()
      );
    } catch (error) {
      this.report(error, 'Could not open Stripe. Please try again.');
      this.opening.set(null);
    }
  }

  private report(error: unknown, fallback: string) {
    this.error.set(
      error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
        ? error.error.message
        : fallback
    );
  }
}
