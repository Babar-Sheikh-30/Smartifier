import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [RouterLink, FormsModule],
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
