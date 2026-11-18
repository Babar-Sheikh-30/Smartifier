import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LandingComponent } from './landing';

describe('Landing page', () => {
  it('explains the value, accurate delivery conditions, memberships and account actions', async () => {
    await TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [provideZonelessChangeDetection()]
    }).compileComponents();
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const text = page.textContent ?? '';
    expect(text).toContain('Find the right words');
    expect(text).toContain('Have an interview coming up');
    expect(text).toContain('SMS opt-in');
    expect(text).toContain('Daily texts are in development');
    expect(text).toContain('Suggest shared-stack content');
    expect(text).toContain('Stripe Checkout');
    expect(text).not.toContain('higher text-message allowance');
    expect(page.querySelector('.hero-actions a[href="/register"]')).not.toBeNull();
    expect(page.querySelector('nav a[href="/login"]')).not.toBeNull();
    expect(page.querySelector('.hero-actions a[href="#demo"]')).not.toBeNull();
    expect(page.querySelectorAll('a[href="#demo"]')).toHaveLength(1);
    const previews = page.querySelectorAll<HTMLButtonElement>('#demo .stack-preview');
    expect(page.querySelector('.message-bubble')?.textContent).toContain('initiative');
    previews[1].click();
    fixture.detectChanges();
    expect(page.querySelector('.message-bubble')?.textContent).toContain('Venus');
    expect(previews[1].getAttribute('aria-pressed')).toBe('true');
    expect(previews[0].getAttribute('aria-pressed')).toBe('false');
  });
});
