import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth.service';

export type ContentType = 'QUOTE' | 'FACT' | 'JOKE' | 'VOCABULARY';
export interface ContentItem {
  id?: string;
  title?: string | null;
  description?: string | null;
  type: ContentType;
  body: string;
  answer: string;
  attribution: string;
}
export interface StackVersion {
  number: number;
  name: string;
  description: string;
  items: ContentItem[];
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
  createdAt: string;
  reviewNote: string | null;
}
export interface PersonalStack {
  id: string;
  // Only copies have a source; stacks created from scratch leave both source fields null.
  sourceStackId: string | null;
  sourceVersion: number | null;
  // Database concurrency counter, separate from the content version number.
  revision: number;
  versions: StackVersion[];
}
export interface DefaultStack {
  id: string;
  name: string;
  description: string;
  itemCount: number;
}
export interface StackEdit {
  name: string;
  description: string;
  items: ContentItem[];
  expectedRevision: number;
}

@Injectable({ providedIn: 'root' })
export class StackService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly base = '/api/member/stacks';

  defaults() {
    return firstValueFrom(this.http.get<DefaultStack[]>(`${this.base}/defaults`));
  }
  personal() {
    return firstValueFrom(this.http.get<PersonalStack[]>(this.base));
  }
  private async post(path: string, body: unknown) {
    return firstValueFrom(
      this.http.post<PersonalStack>(this.base + path, body, { headers: await this.auth.headers() })
    );
  }
  create(edit: StackEdit) {
    return this.post('', edit);
  }
  save(stack: PersonalStack, edit: StackEdit) {
    return this.post(`/${encodeURIComponent(stack.id)}/versions`, edit);
  }
  copy(id: string) {
    return this.post(`/${encodeURIComponent(id)}/copy`, {});
  }
  submit(stack: PersonalStack) {
    return this.post(`/${encodeURIComponent(stack.id)}/submit`, {
      expectedRevision: stack.revision
    });
  }
  cancelSubmission(stack: PersonalStack, version: number) {
    return this.post(`/${encodeURIComponent(stack.id)}/versions/${version}/cancel-submission`, {
      expectedRevision: stack.revision
    });
  }
  async remove(stack: PersonalStack) {
    await firstValueFrom(
      this.http.post<void>(
        `${this.base}/${encodeURIComponent(stack.id)}/remove`,
        { expectedRevision: stack.revision },
        { headers: await this.auth.headers() }
      )
    );
  }
}
