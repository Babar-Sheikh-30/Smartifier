import { Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MemberStacksComponent } from '../member-stacks/member-stacks';
import { AdminContentComponent } from '../admin-content/admin-content';
import { AdminReviewComponent } from '../admin-review/admin-review';
import { AdminPricingComponent } from '../admin-pricing/admin-pricing';
import { MemberBillingComponent } from '../member-billing/member-billing';

const profileTab = { id: 'profile', label: 'Profile' };
const memberTabs = [
  { id: 'stacks', label: 'My stacks' },
  { id: 'billing', label: 'Membership & billing' },
  { id: 'notifications', label: 'Notifications' },
  profileTab
];
const adminTabs = [
  { id: 'review', label: 'Review queue' },
  { id: 'content', label: 'Content' },
  { id: 'pricing', label: 'Membership pricing' },
  profileTab
];

@Component({
  selector: 'bs-dashboard',
  standalone: true,
  imports: [
    RouterLink,
    FormsModule,
    MemberStacksComponent,
    AdminContentComponent,
    AdminReviewComponent,
    AdminPricingComponent,
    MemberBillingComponent
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class DashboardComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap
  });
  readonly tabs = computed(() => (this.auth.user()?.role === 'ADMIN' ? adminTabs : memberTabs));
  readonly activeTab = computed(() => {
    const query = this.query();
    const requested = query.get('tab') ?? (query.has('checkout') ? 'billing' : null);
    return this.tabs().find((tab) => tab.id === requested)?.id ?? this.tabs()[0].id;
  });
  readonly busy = signal(false);
  readonly error = signal('');
  readonly saving = signal('');
  readonly notice = signal('');
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

  async selectTab(id: string, focusTab = false) {
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: id },
      queryParamsHandling: 'merge'
    });
    if (focusTab) this.element.nativeElement.querySelector<HTMLElement>('#tab-' + id)?.focus();
  }

  navigateTabs(event: KeyboardEvent) {
    const tabs = Array.from((event.currentTarget as HTMLElement).querySelectorAll('button'));
    const current = tabs.indexOf(event.target as HTMLButtonElement);
    if (current < 0) return;
    const next = {
      ArrowRight: (current + 1) % tabs.length,
      ArrowLeft: (current - 1 + tabs.length) % tabs.length,
      Home: 0,
      End: tabs.length - 1
    }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    tabs[next].focus();
    void this.selectTab(this.tabs()[next].id);
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
