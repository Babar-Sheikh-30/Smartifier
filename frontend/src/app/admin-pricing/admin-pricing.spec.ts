import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../auth.service';
import { AdminPricingComponent } from './admin-pricing';

describe('Admin membership pricing', () => {
  let component: AdminPricingComponent;
  let http: HttpTestingController;
  const base = '/api/admin/membership-pricing';
  const price = {
    priceId: 'price_old',
    revision: 0,
    amount: 1000,
    currency: 'cad',
    interval: 'month',
    live: false,
    configured: true,
    editable: true
  };
  const yearly = { ...price, priceId: 'price_new', revision: 1, amount: 10000, interval: 'year' };
  const preview = {
    nextCursor: null,
    target: yearly,
    skipped: 0,
    more: false,
    candidates: [
      {
        id: 'review-1',
        subscriptionId: 'sub_member',
        oldAmount: 1000,
        currency: 'cad',
        interval: 'month'
      }
    ]
  };
  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [AdminPricingComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { headers: vi.fn().mockResolvedValue({ 'X-CSRF-TOKEN': 'csrf' }) }
        }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AdminPricingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    http.expectOne(base).flush(price);
    await fixture.whenStable();
  });
  afterEach(() => http.verify());
  async function tick() {
    await Promise.resolve();
    await Promise.resolve();
  }
  function edit(scope: string) {
    component.amount = '100.00';
    component.interval = 'year';
    component.scope = scope;
    component.review();
  }
  it('saves new-member pricing without changing any existing subscriptions', async () => {
    edit('new');
    const saving = component.save();
    await tick();
    const request = http.expectOne(base);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf');
    expect(request.request.body).toEqual({
      amount: 10000,
      currency: 'cad',
      interval: 'year',
      expectedRevision: 0
    });
    request.flush(yearly);
    await saving;
    http.expectNone(base + '/preview');
    expect(component.preview()).toBeNull();
    expect(component.notice()).toContain('Existing subscriptions have not changed');
  });
  it('requires separate confirmation before applying existing subscription changes', async () => {
    edit('both');
    const saving = component.save();
    await tick();
    http.expectOne(base).flush(yearly);
    await tick();
    http.expectOne(base + '/preview').flush(preview);
    await saving;
    await component.apply();
    http.expectNone(base + '/apply/review-1');
    component.confirmed = true;
    const applying = component.apply();
    await tick();
    http
      .expectOne(base + '/apply/review-1')
      .flush({ status: 'updated', message: 'Subscription updated.' });
    await applying;
    expect(component.results()['review-1']).toBe('Subscription updated.');
    component.confirmed = true;
    await component.apply();
    http.expectNone(base + '/apply/review-1');
  });
  it('can review the next batch without changing subscriptions', async () => {
    component.pricing.set(yearly);
    const loading = component.reviewExisting('0:sub_25');
    await tick();
    const request = http.expectOne(base + '/preview');
    expect(request.request.body).toEqual({ priceId: 'price_new', cursor: '0:sub_25' });
    request.flush(preview);
    await loading;
    http.expectNone(base + '/apply/review-1');
    expect(component.confirmed).toBe(false);
  });
  it('reports partial progress and prevents replay when a request fails', async () => {
    component.pricing.set(yearly);
    component.preview.set({
      ...preview,
      candidates: [...preview.candidates, { ...preview.candidates[0], id: 'review-2' }]
    });
    component.confirmed = true;
    const applying = component.apply();
    await tick();
    http
      .expectOne(base + '/apply/review-1')
      .flush({ status: 'updated', message: 'Subscription updated.' });
    await tick();
    http.expectOne(base + '/apply/review-2').flush({}, { status: 409, statusText: 'Conflict' });
    await applying;
    expect(component.results()['review-1']).toBe('Subscription updated.');
    expect(component.notice()).toContain('Some changes may already have completed');
    expect(component.confirmed).toBe(false);
  });
});
