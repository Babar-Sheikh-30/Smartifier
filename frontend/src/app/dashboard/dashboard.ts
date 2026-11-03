import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MemberStacksComponent } from '../member-stacks/member-stacks';
import { AdminContentComponent } from '../admin-content/admin-content';

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [RouterLink, FormsModule, MemberStacksComponent, AdminContentComponent],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class DashboardComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly saving = signal('');
  readonly notice = signal('');
  readonly paying = signal(false);
  private readonly checkoutResult = inject(ActivatedRoute).snapshot.queryParamMap.get('checkout');
  readonly paymentNotice =
    // A checkout return does not prove payment; the server waits for Stripe's webhook.
    this.checkoutResult === 'returned'
      ? 'Your payment is being confirmed. If premium is not active yet, refresh this page in a few seconds.'
      : this.checkoutResult === 'cancelled'
        ? 'Checkout was cancelled. You can try again whenever you are ready.'
        : '';
  phoneNumber = '';

  async payForPremium() {
    if (this.paying() || this.busy()) return;
    this.paying.set(true);
    this.error.set('');
    try {
      const url = await this.auth.createCheckout();
      window.location.assign(url);
    } catch (error) {
      this.error.set(
        error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
          ? error.error.message
          : 'Could not open Stripe checkout. Please try again.'
      );
      this.paying.set(false);
    }
  }

  async savePhone() {
    if (this.saving() || this.busy()) return;
    this.saving.set('phone');
    this.error.set('');
    this.notice.set('');
    try {
      await this.auth.updatePhone(this.phoneNumber);
      this.phoneNumber = '';
      this.notice.set('Mobile number saved.');
    } catch (error) {
      this.error.set(
        error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
          ? error.error.message
          : 'Could not save your number. Please try again.'
      );
    } finally {
      this.saving.set('');
    }
  }

  async logout() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.logout();
      await this.router.navigateByUrl('/login');
    } catch {
      this.error.set('Could not log out. Please try again.');
    } finally {
      this.busy.set(false);
    }
  }
}
