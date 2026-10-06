import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface Profile {
  id: string;
  name: string;
  email: string;
  role: 'MEMBER' | 'ADMIN';
  createdAt: string;
  phoneNumber: string | null;
  subscriptions: string[];
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
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

  private async headers(): Promise<Record<string, string>> {
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

  async login(email: string, password: string): Promise<Profile> {
    const body = new URLSearchParams({ email, password }).toString();
    await firstValueFrom(
      this.http.post('/api/auth/login', body, {
        headers: { ...(await this.headers()), 'Content-Type': 'application/x-www-form-urlencoded' }
      })
    );
    return this.loadUser();
  }

  async logout() {
    await firstValueFrom(this.http.post('/api/auth/logout', {}, { headers: await this.headers() }));
    this.user.set(null);
  }

  dashboard(user: Profile): string {
    return user.role === 'ADMIN' ? '/admin/dashboard' : '/member/dashboard';
  }
}
