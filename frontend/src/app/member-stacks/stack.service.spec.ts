import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../auth.service';
import { PersonalStack, StackEdit, StackService } from './stack.service';

describe('StackService', () => {
  let service: StackService;
  let http: HttpTestingController;
  const stack: PersonalStack = {
    id: 'personal/one',
    sourceStackId: null,
    sourceVersion: null,
    revision: 7,
    versions: []
  };
  const edit: StackEdit = {
    name: 'My stack',
    description: 'A personal selection',
    items: [],
    expectedRevision: 7
  };

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
    service = TestBed.inject(StackService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('loads the default stack catalog', async () => {
    const pending = service.defaults();
    const defaults = [{ id: 'quotes', name: 'Quotes', description: 'Daily quotes', itemCount: 3 }];
    http.expectOne('/api/member/stacks/defaults').flush(defaults);
    await expect(pending).resolves.toEqual(defaults);
  });

  it('creates a personal stack with CSRF protection', async () => {
    const pending = service.create(edit);
    await Promise.resolve();
    const request = http.expectOne('/api/member/stacks');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    expect(request.request.body).toEqual(edit);
    request.flush(stack);
    await expect(pending).resolves.toEqual(stack);
  });

  it('encodes stack IDs and includes the expected revision when saving', async () => {
    const pending = service.save(stack, edit);
    await Promise.resolve();
    const request = http.expectOne('/api/member/stacks/personal%2Fone/versions');
    expect(request.request.body).toEqual(edit);
    request.flush(stack);
    await pending;
  });

  it('submits the current revision for review', async () => {
    const pending = service.submit(stack);
    await Promise.resolve();
    const request = http.expectOne('/api/member/stacks/personal%2Fone/submit');
    expect(request.request.body).toEqual({ expectedRevision: 7 });
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    request.flush(stack);
    await pending;
  });

  it('propagates revision conflicts so the UI can handle them', async () => {
    const pending = service.save(stack, edit);
    const rejected = expect(pending).rejects.toMatchObject({ status: 409 });
    await Promise.resolve();
    http
      .expectOne('/api/member/stacks/personal%2Fone/versions')
      .flush(
        { message: 'Stack changed; reload before saving.' },
        { status: 409, statusText: 'Conflict' }
      );
    await rejected;
  });

  it('cancels a specific version with its loaded revision and CSRF token', async () => {
    const pending = service.cancelSubmission(stack, 2);
    await Promise.resolve();
    const request = http.expectOne(
      '/api/member/stacks/personal%2Fone/versions/2/cancel-submission'
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    expect(request.request.body).toEqual({ expectedRevision: 7 });
    request.flush(stack);
    await expect(pending).resolves.toEqual(stack);
  });

  it('removes a stack with CSRF and its loaded revision', async () => {
    const pending = service.remove(stack);
    await Promise.resolve();
    const request = http.expectOne('/api/member/stacks/personal%2Fone/remove');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('token');
    expect(request.request.body).toEqual({ expectedRevision: 7 });
    request.flush(null);
    await pending;
  });
});
