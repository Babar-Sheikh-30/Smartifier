import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { NgForm } from '@angular/forms';
import { beforeEach, describe, expect, it, vi, MockInstance } from 'vitest';
import { AuthService } from '../auth.service';
import { AuthPageComponent } from './auth-page';

describe('Authentication form', () => {
  let fixture: ComponentFixture<AuthPageComponent>;
  const route = { snapshot: { data: { register: false }, queryParamMap: convertToParamMap({}) } };
  let auth: {
    login: ReturnType<typeof vi.fn>;
    register: ReturnType<typeof vi.fn>;
    dashboard: ReturnType<typeof vi.fn>;
  };
  let navigate: MockInstance<Router['navigate']>;
  let navigateByUrl: MockInstance<Router['navigateByUrl']>;

  beforeEach(async () => {
    route.snapshot.data.register = false;
    auth = {
      login: vi.fn().mockResolvedValue({ role: 'MEMBER' }),
      register: vi.fn().mockResolvedValue(undefined),
      dashboard: vi.fn().mockReturnValue('/member/dashboard')
    };
    await TestBed.configureTestingModule({
      imports: [AuthPageComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: AuthService, useValue: auth }
      ]
    }).compileComponents();
    const router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    navigateByUrl = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(AuthPageComponent);
  });

  async function render(register = false) {
    route.snapshot.data.register = register;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function fill(values: Record<string, string>) {
    for (const [id, value] of Object.entries(values)) {
      const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function submit() {
    const form = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
    await fixture.componentInstance.submit(form);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const registration = {
    name: 'Test Member',
    email: 'member@example.com',
    phone: '14165551234',
    password: 'StrongPass123!',
    confirm: 'StrongPass123!'
  };

  it('shows login fields without registration-only fields or premature errors', async () => {
    await render();
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Log in');
    expect(fixture.nativeElement.querySelector('#confirm')).toBeNull();
    expect(fixture.nativeElement.querySelector('#phone')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[aria-invalid="true"]')).toBeNull();
  });

  it('marks empty fields invalid and prevents an empty login', async () => {
    await render();
    await submit();
    expect(auth.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#email').getAttribute('aria-invalid')).toBe('true');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Needs attention'
    );
  });

  it('allows existing short passwords at login and clears the password after success', async () => {
    await render();
    await fill({ email: 'member@example.com', password: 'old' });
    await submit();
    expect(auth.login).toHaveBeenCalledWith('member@example.com', 'old');
    expect(navigateByUrl).toHaveBeenCalledWith('/member/dashboard');
    expect(fixture.componentInstance.password).toBe('');
  });

  it('blocks registration when password confirmation differs', async () => {
    await render(true);
    await fill({ ...registration, confirm: 'DifferentPass123!' });
    await submit();
    expect(auth.register).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#confirm-feedback').textContent).toContain(
      'Passwords do not match'
    );
  });

  it('blocks a weak registration password', async () => {
    await render(true);
    await fill({ ...registration, password: 'onlylowercase', confirm: 'onlylowercase' });
    await submit();
    expect(auth.register).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#password').getAttribute('aria-invalid')).toBe(
      'true'
    );
  });

  it('rejects a Unicode password exceeding the BCrypt byte limit', async () => {
    await render(true);
    const password = 'Aa1!' + 'é'.repeat(35);
    await fill({ ...registration, password, confirm: password });
    await submit();
    expect(auth.register).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('This password is too large to save');
  });

  it('rejects a phone number without the required international format', async () => {
    await render(true);
    await fill({ ...registration, phone: '416-555-1234' });
    await submit();
    expect(auth.register).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#phone').getAttribute('aria-invalid')).toBe('true');
  });

  it('registers valid details, clears passwords, and redirects to login', async () => {
    await render(true);
    await fill(registration);
    await submit();
    expect(auth.register).toHaveBeenCalledWith(
      'Test Member',
      'member@example.com',
      'StrongPass123!',
      'StrongPass123!',
      '14165551234'
    );
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registered: '1' } });
    expect(fixture.componentInstance.password).toBe('');
    expect(fixture.componentInstance.passwordConfirmation).toBe('');
  });

  it('displays server errors and re-enables submission', async () => {
    auth.login.mockRejectedValue(
      new HttpErrorResponse({ status: 401, error: { message: 'Invalid email or password.' } })
    );
    await render();
    await fill({ email: 'member@example.com', password: 'wrong' });
    await submit();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Invalid email or password.'
    );
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(false);
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows a useful fallback when the network request fails', async () => {
    auth.login.mockRejectedValue(new Error('offline'));
    await render();
    await fill({ email: 'member@example.com', password: 'password' });
    await submit();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Could not connect. Please try again.'
    );
  });

  it('disables the button and prevents duplicate login requests while busy', async () => {
    let resolveLogin!: (user: { role: string }) => void;
    auth.login.mockReturnValue(
      new Promise((resolve) => {
        resolveLogin = resolve;
      })
    );
    await render();
    await fill({ email: 'member@example.com', password: 'password' });
    const form = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
    const pending = fixture.componentInstance.submit(form);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    await fixture.componentInstance.submit(form);
    expect(auth.login).toHaveBeenCalledTimes(1);
    resolveLogin({ role: 'MEMBER' });
    await pending;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(false);
  });
});
