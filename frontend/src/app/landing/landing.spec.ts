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
    expect(text).toContain('WHY SMARTIFIER?');
    expect(text).toContain('Your interests lead');
    expect(text).toContain('Get Daily Text');
    expect(text).toContain('SMS opt-in');
    expect(text).toContain('enabled delivery');
    expect(text).toContain('Daily knowledge texts are not ready yet');
    expect(text).toContain('Suggest shared-stack content');
    expect(text).toContain('Stripe Checkout');
    expect(text).not.toContain('higher text-message allowance');
    expect(page.querySelector('.hero-actions a[href="/register"]')).not.toBeNull();
    expect(page.querySelector('.hero-actions a[href="/login"]')).not.toBeNull();
    const previews = page.querySelectorAll<HTMLButtonElement>('.stack-preview');
    previews[1].click();
    fixture.detectChanges();
    expect(page.querySelector('.message-bubble')?.textContent).toContain('anagram');
    expect(previews[1].getAttribute('aria-pressed')).toBe('true');
  });
});
