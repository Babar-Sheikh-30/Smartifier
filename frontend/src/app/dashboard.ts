import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [RouterLink],
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
            <dt>Account type</dt>
            <dd>{{ user.role === 'ADMIN' ? 'Administrator' : 'Member' }}</dd>
          </dl>
          <p class="field-hint">More features are coming soon.</p>
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
