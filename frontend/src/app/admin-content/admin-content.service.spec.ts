import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../auth.service';
import { AdminContentService, AdminStack } from './admin-content.service';

describe('AdminContentService', () => {
  let service: AdminContentService;
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
    service = TestBed.inject(AdminContentService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('sends CSRF and the loaded revision for admin mutations', async () => {
    const stack: AdminStack = {
      id: 'shared/one',
      revision: 8,
      publishedVersion: null,
      versions: []
    };
    const pending = service.change(stack, 'content/item/publish');
    await Promise.resolve();
    const request = http.expectOne('/api/admin/stacks/shared%2Fone/content/item/publish');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ expectedRevision: 8 });
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    request.flush(stack);
    await pending;
  });

  it('loads shared stacks from the admin endpoint', async () => {
    const pending = service.list();
    http.expectOne('/api/admin/stacks').flush([]);
    await expect(pending).resolves.toEqual([]);
  });
});
