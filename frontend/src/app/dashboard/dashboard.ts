
import { Component, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MemberStacksComponent } from '../member-stacks/member-stacks';
import { AdminContentComponent } from '../admin-content/admin-content';
import { AdminReviewComponent } from '../admin-review/admin-review';

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [
    RouterLink,
    FormsModule,
    MemberStacksComponent,
    AdminContentComponent,
    AdminReviewComponent
  ],
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

  // SMS preferences: separate from existing dashboard functionality.
  readonly smsOptedIn = signal(false);
  readonly smsLoading = signal(true);
  readonly smsSaving = signal(false);
  readonly smsError = signal('');
  readonly smsNotice = signal('');

  private smsLoadedAccountId: string | null = null;
  private savedSmsPreference = false;

  constructor() {
    // Load SMS settings when the authenticated member becomes available.
    effect(() => {
      const user = this.auth.user();

      if (user?.role === 'MEMBER' && this.smsLoadedAccountId !== user.id) {
        this.smsLoadedAccountId = user.id;
        void this.loadSmsSettings();
      }
    });
  }

  // Retrieve the current SMS preference from the backend.
  async loadSmsSettings() {
    this.smsLoading.set(true);
    this.smsError.set('');

    try {
      const settings = await this.auth.getSmsSettings();
      this.smsOptedIn.set(settings.optedIn);
      this.savedSmsPreference = settings.optedIn;
    } catch {
      this.smsError.set('Could not load your SMS preferences. Please refresh the page.');
    } finally {
      this.smsLoading.set(false);
    }
  }

  // Save an explicit SMS opt-in or opt-out choice.
  async saveSmsSettings() {
    if (this.smsSaving() || this.smsLoading() || this.busy()) return;

    this.smsSaving.set(true);
    this.smsError.set('');
    this.smsNotice.set('');

    try {
      const settings = await this.auth.updateSmsSettings(this.smsOptedIn());

      this.smsOptedIn.set(settings.optedIn);
      this.savedSmsPreference = settings.optedIn;

      this.smsNotice.set(
        settings.optedIn
          ? 'SMS notification preference enabled.'
          : 'SMS notification preference disabled.'
      );
    } catch {
      this.smsOptedIn.set(this.savedSmsPreference);
      this.smsError.set('Could not update your SMS preferences. Please try again.');
    } finally {
      this.smsSaving.set(false);
    }
  }

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
      await this.loadSmsSettings();
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
