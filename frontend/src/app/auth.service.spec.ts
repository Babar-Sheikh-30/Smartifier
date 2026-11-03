import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthService, Profile } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  const member: Profile = {
    id: 'member-1',
    name: 'Member',
    email: 'member@example.com',
    role: 'MEMBER',
    createdAt: '2026-01-01T00:00:00Z',
    phoneNumber: null,
    subscriptions: [],
    premium: false
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()]
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function csrf() {
    http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' });
    await Promise.resolve();
    await Promise.resolve();
  }

  it('restores the profile from the server session', async () => {
    expect(auth.user()).toBeNull();
    const pending = auth.loadUser();
    http.expectOne('/api/auth/me').flush(member);
    await expect(pending).resolves.toEqual(member);
    expect(auth.user()).toEqual(member);
  });

  it('clears a stale profile when session lookup fails', async () => {
    auth.user.set(member);
    const pending = auth.loadUser();
    const rejected = expect(pending).rejects.toMatchObject({ status: 401 });
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    await rejected;
    expect(auth.user()).toBeNull();
  });

  it('URL-encodes login credentials and sends a CSRF token before loading the profile', async () => {
    const pending = auth.login('member+test@example.com', 'A&password=123!');
    await csrf();
    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('fresh-token');
    expect(request.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(new URLSearchParams(request.request.body).get('email')).toBe('member+test@example.com');
    expect(new URLSearchParams(request.request.body).get('password')).toBe('A&password=123!');
    request.flush({});
    await Promise.resolve();
    http.expectOne('/api/auth/me').flush(member);
    await expect(pending).resolves.toEqual(member);
  });

  it('registers with confirmation and phone without assigning a role', async () => {
    const pending = auth.register(
      'Member',
      member.email,
      'StrongPass123!',
      'StrongPass123!',
      '14165551234'
    );
    await csrf();
    const request = http.expectOne('/api/auth/register');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('fresh-token');
    expect(request.request.body).toEqual({
      name: 'Member',
      email: member.email,
      password: 'StrongPass123!',
      passwordConfirmation: 'StrongPass123!',
      phoneNumber: '14165551234'
    });
    request.flush({});
    await pending;
  });

  it('clears the local profile after successful logout', async () => {
    auth.user.set(member);
    const pending = auth.logout();
    await csrf();
    const request = http.expectOne('/api/auth/logout');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('fresh-token');
    request.flush({});
    await pending;
    expect(auth.user()).toBeNull();
  });

  it('retains the profile when logout fails', async () => {
    auth.user.set(member);
    const pending = auth.logout();
    const rejected = expect(pending).rejects.toMatchObject({ status: 500 });
    await csrf();
    http.expectOne('/api/auth/logout').flush({}, { status: 500, statusText: 'Server Error' });
    await rejected;
    expect(auth.user()).toEqual(member);
  });

  it('updates the profile after a subscription change', async () => {
    const pending = auth.subscribe('quotes', true);
    await csrf();
    const request = http.expectOne('/api/member/subscriptions');
    expect(request.request.body).toEqual({ stack: 'quotes', subscribed: true });
    const updated = { ...member, subscriptions: ['quotes'] };
    request.flush(updated);
    await pending;
    expect(auth.user()).toEqual(updated);
  });

  it('routes admins and members to their respective dashboards', () => {
    expect(auth.dashboard(member)).toBe('/member/dashboard');
    expect(auth.dashboard({ ...member, role: 'ADMIN' })).toBe('/admin/dashboard');
  });
});
