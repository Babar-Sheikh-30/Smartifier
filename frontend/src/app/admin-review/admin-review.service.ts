import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth.service';
import { StackVersion } from '../member-stacks/stack.service';

export interface ReviewEntry {
  stackId: string;
  revision: number;
  memberName: string;
  memberEmail: string;
  version: StackVersion & { submittedAt: string | null };
}

@Injectable({ providedIn: 'root' })
export class AdminReviewService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly base = '/api/admin/reviews';

  pending() {
    return firstValueFrom(this.http.get<ReviewEntry[]>(this.base));
  }
  async decide(entry: ReviewEntry, approved: boolean, note: string) {
    return firstValueFrom(
      this.http.post<void>(
        `${this.base}/${encodeURIComponent(entry.stackId)}/versions/${entry.version.number}/decision`,
        { expectedRevision: entry.revision, approved, note: note.trim() },
        { headers: await this.auth.headers() }
      )
    );
  }
}
