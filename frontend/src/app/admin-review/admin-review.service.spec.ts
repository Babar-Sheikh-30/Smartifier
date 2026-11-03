import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../auth.service';
import { AdminReviewService, ReviewEntry } from './admin-review.service';

describe('AdminReviewService', () => {
  let service: AdminReviewService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { headers: vi.fn().mockResolvedValue({ 'X-CSRF-TOKEN': 'token' }) }
        }
      ]
    });
    service = TestBed.inject(AdminReviewService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('loads the admin queue', async () => {
    const pending = service.pending();
    http.expectOne('/api/admin/reviews').flush([]);
    await expect(pending).resolves.toEqual([]);
  });
  it('includes the submitted version, expected revision, decision, note and CSRF token', async () => {
    const entry = { stackId: 'personal/one', revision: 8, version: { number: 2 } } as ReviewEntry;
    const pending = service.decide(entry, false, ' Please add sources. ');
    await Promise.resolve();
    const request = http.expectOne('/api/admin/reviews/personal%2Fone/versions/2/decision');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    expect(request.request.body).toEqual({
      expectedRevision: 8,
      approved: false,
      note: 'Please add sources.'
    });
    request.flush(null);
    await pending;
  });
});
