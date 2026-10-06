import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [RouterLink, FormsModule],
  template: `
    <header class="navigation wrap">
      <a class="brand" routerLink="/">Smartifier<span class="brand-dot">.</span></a>
      <button class="button button-small button-outline" (click)="logout()" [disabled]="busy()">
        {{ busy() ? 'Logging out…' : 'Log out' }}
      </button>
    </header>
    <main class="account-page wrap">
      @if (auth.user(); as user) {
        <section class="account-card dashboard-card">
          <p class="eyebrow">
            {{ user.role === 'ADMIN' ? 'ADMIN DASHBOARD' : 'MEMBER DASHBOARD' }}
          </p>
          <h1>Welcome, {{ user.name }}.</h1>
          <p class="account-intro">
            {{
              user.role === 'ADMIN'
                ? 'Your administration dashboard is ready.'
                : 'Welcome to your Smartifier account.'
            }}
          </p>
          <dl class="profile-details">
            <dt>Email</dt>
            <dd>{{ user.email }}</dd>
            @if (user.role === 'MEMBER') {
              <dt>Mobile number</dt>
              <dd>{{ user.phoneNumber || 'No mobile number on file' }}</dd>
            }
            <dt>Account type</dt>
            <dd>{{ user.role === 'ADMIN' ? 'Administrator' : 'Member' }}</dd>
          </dl>
          @if (user.role === 'MEMBER') {
            <form #phoneForm="ngForm" (ngSubmit)="savePhone()">
              <label for="member-phone">Update your mobile number</label>
              <input
                id="member-phone"
                name="phoneNumber"
                type="tel"
                autocomplete="tel"
                required
                maxlength="40"
                pattern="[+][1-9][0-9]{7,14}"
                placeholder="+14165551234"
                [(ngModel)]="phoneNumber"
                aria-describedby="member-phone-hint"
              />
              <p id="member-phone-hint" class="field-hint">
                Include your country code, using digits only after + (for example, +14165551234).
              </p>
              <button
                class="button button-small"
                type="submit"
                [disabled]="phoneForm.invalid || saving() || busy()"
              >
                {{ saving() === 'phone' ? 'Saving...' : 'Save number' }}
              </button>
            </form>
            <section class="premium-banner" aria-labelledby="premium-title">
              <div>
                <p class="eyebrow">GO PREMIUM</p>
                <h2 id="premium-title">Keep your curiosity growing.</h2>
                <p>Upgrade your membership when premium becomes available.</p>
              </div>
              <button class="button" (click)="premiumNotice.set(true)">Pay for premium</button>
            </section>
            @if (premiumNotice()) {
              <p class="form-success" role="status">
                Premium payments are coming soon. No payment has been taken.
              </p>
            }
            <section aria-labelledby="stacks-title" class="member-stacks">
              <p class="eyebrow">YOUR FREE STACKS</p>
              <h2 id="stacks-title">Choose what sparks your curiosity.</h2>
              <p class="account-intro">
                Subscribe to any of these three stacks. The knowledge will arrive by text; your
                account only manages your subscriptions.
              </p>
              <p class="field-hint">
                You can save your subscriptions now. Text delivery is coming soon.
              </p>
              @if (!user.phoneNumber) {
                <p class="form-error">Add your mobile number above before subscribing.</p>
              }
              <div class="stack-options">
                @for (stack of stacks; track stack.id) {
                  <article class="stack-option">
                    <h3>{{ stack.name }}</h3>
                    <p class="field-hint">
                      {{
                        subscribed(stack.id) ? 'Subscribed' : 'Available with your free membership'
                      }}
                    </p>
                    <button
                      class="button button-small"
                      [disabled]="
                        saving() || busy() || (!user.phoneNumber && !subscribed(stack.id))
                      "
                      [attr.aria-pressed]="subscribed(stack.id)"
                      (click)="toggleStack(stack.id)"
                    >
                      {{
                        saving() === stack.id
                          ? 'Saving...'
                          : subscribed(stack.id)
                            ? 'Unsubscribe'
                            : 'Subscribe'
                      }}
                    </button>
                  </article>
                }
              </div>
              @if (notice()) {
                <p class="form-success" role="status">{{ notice() }}</p>
              }
            </section>
          }
          @if (error()) {
            <p class="form-error" role="alert">{{ error() }}</p>
          }
        </section>
      }
    </main>
  `
})
export class DashboardComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly saving = signal('');
  readonly notice = signal('');
  readonly premiumNotice = signal(false);
  phoneNumber = '';
  readonly stacks = [
    { id: 'everyday-science', name: 'Everyday science' },
    { id: 'words-and-language', name: 'Words and language' },
    { id: 'learning-habits', name: 'Learning habits' }
  ];

  subscribed(id: string) {
    return this.auth.user()?.subscriptions.includes(id) ?? false;
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

  async toggleStack(id: string) {
    if (this.saving() || this.busy()) return;
    const subscribe = !this.subscribed(id);
    this.saving.set(id);
    this.error.set('');
    this.notice.set('');
    try {
      await this.auth.subscribe(id, subscribe);
      this.notice.set(
        subscribe
          ? 'Subscription saved. Text delivery is coming soon.'
          : 'You have unsubscribed from this stack.'
      );
    } catch (error) {
      this.error.set(
        error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
          ? error.error.message
          : 'Could not save your subscription. Please try again.'
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
