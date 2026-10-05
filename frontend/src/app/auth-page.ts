import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'bs-auth-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './auth-page.html'
})
export class AuthPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly route = inject(ActivatedRoute);
  readonly busy = signal(false);
  readonly error = signal('');
  name = '';
  email = '';
  password = '';
  passwordConfirmation = '';

  get registering() {
    return this.route.snapshot.data['register'] === true;
  }

  async submit() {
    if (this.busy()) return;
    this.error.set('');
    if (this.registering && this.password !== this.passwordConfirmation) {
      this.error.set('Passwords do not match.');
      return;
    }
    this.busy.set(true);
    try {
      if (this.registering) {
        await this.auth.register(this.name, this.email, this.password, this.passwordConfirmation);
        this.password = '';
        this.passwordConfirmation = '';
        await this.router.navigate(['/login'], { queryParams: { registered: '1' } });
      } else {
        const user = await this.auth.login(this.email, this.password);
        this.password = '';
        await this.router.navigateByUrl(this.auth.dashboard(user));
      }
    } catch (error) {
      const message =
        error instanceof HttpErrorResponse && typeof error.error?.message === 'string'
          ? error.error.message
          : 'Could not connect. Please try again.';
      this.error.set(message);
    } finally {
      this.busy.set(false);
    }
  }
}
