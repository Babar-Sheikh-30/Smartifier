
import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface Profile {
  id: string;
  name: string;
  email: string;
  role: 'MEMBER' | 'ADMIN';
  createdAt: string;
  // Admins and older member accounts may not have a messaging number.
  phoneNumber: string | null;
  subscriptions: string[];
  premium: boolean;
}

// SMS preference returned by the backend.
export interface SmsSettings {
  optedIn: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  // No profile is assumed until the server confirms the current session.
  readonly user = signal<Profile | null>(null);

  async loadUser(): Promise<Profile> {
    try {
      const user = await firstValueFrom(this.http.get<Profile>('/api/auth/me'));
      this.user.set(user);
      return user;
    } catch (error) {
      this.user.set(null);
      throw error;
    }
  }

  async headers(): Promise<Record<string, string>> {
    // Fetch a fresh token after login/logout when Spring rotates it.
    const csrf = await firstValueFrom(
      this.http.get<{ headerName: string; token: string }>('/api/auth/csrf')
    );
    return { [csrf.headerName]: csrf.token };
  }

  async register(
    name: string,
    email: string,
    password: string,
    passwordConfirmation: string,
    phoneNumber: string
  ) {
    await firstValueFrom(
      this.http.post(
        '/api/auth/register',
        { name, email, password, passwordConfirmation, phoneNumber },
        { headers: await this.headers() }
      )
    );
  }

  async subscribe(stack: string, subscribed: boolean) {
    const user = await firstValueFrom(
      this.http.post<Profile>(
        '/api/member/subscriptions',
        { stack, subscribed },
        { headers: await this.headers() }
      )
    );
    this.user.set(user);
  }

  async updatePhone(phoneNumber: string) {
    const user = await firstValueFrom(
      this.http.post<Profile>(
        '/api/member/phone',
        { phoneNumber },
        { headers: await this.headers() }
      )
    );
    this.user.set(user);
  }

  // Retrieve the member's current SMS preference.
  async getSmsSettings(): Promise<SmsSettings> {
    return firstValueFrom(
      this.http.get<SmsSettings>('/api/member/sms')
    );
  }

  // Enable or disable SMS notifications.
  async updateSmsSettings(optedIn: boolean): Promise<SmsSettings> {
    return firstValueFrom(
      this.http.post<SmsSettings>(
        '/api/member/sms',
        { optedIn },
        { headers: await this.headers() }
      )
    );
  }

  async createCheckout(): Promise<string> {
    const checkout = await firstValueFrom(
      this.http.post<{ url: string }>(
        '/api/member/payments/checkout',
        {},
        { headers: await this.headers() }
      )
    );
    return checkout.url;
  }

  async login(email: string, password: string): Promise<Profile> {
    // Spring Security's form login expects URL-encoded fields rather than JSON.
    const body = new URLSearchParams({ email, password }).toString();
    await firstValueFrom(
      this.http.post('/api/auth/login', body, {
        headers: { ...(await this.headers()), 'Content-Type': 'application/x-www-form-urlencoded' }
      })
    );
    return this.loadUser();
  }

  async logout() {
    await firstValueFrom(
      this.http.post('/api/auth/logout', {}, { headers: await this.headers() })
    );
    this.user.set(null);
  }

  dashboard(user: Profile): string {
    return user.role === 'ADMIN' ? '/admin/dashboard' : '/member/dashboard';
  }
}
