import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth.service';
import { ContentItem, StackVersion } from '../member-stacks/stack.service';

export interface AdminStack {
  id: string;
  revision: number;
  publishedVersion: number | null;
  versions: StackVersion[];
}
export interface AdminItem extends ContentItem {
  id: string;
}

@Injectable({ providedIn: 'root' })
export class AdminContentService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly base = '/api/admin/stacks';
  list() {
    return firstValueFrom(this.http.get<AdminStack[]>(this.base));
  }
  async create(name: string, description: string) {
    return firstValueFrom(
      this.http.post<AdminStack>(
        this.base,
        { name, description },
        { headers: await this.auth.headers() }
      )
    );
  }
  async change(stack: AdminStack, action: string, body: object = {}) {
    return firstValueFrom(
      this.http.post<AdminStack>(
        `${this.base}/${encodeURIComponent(stack.id)}/${action}`,
        { ...body, expectedRevision: stack.revision },
        { headers: await this.auth.headers() }
      )
    );
  }
}
