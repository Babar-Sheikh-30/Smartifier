import { Component, ElementRef, inject, signal } from '@angular/core';
import { FormsModule, NgForm, NgModel } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth.service';

@Component({
  selector: 'bs-auth-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './auth-page.html',
  styleUrl: './auth-page.scss'
})
export class AuthPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly route = inject(ActivatedRoute);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly attempted = signal(false);
  name = '';
  email = '';
  phoneNumber = '';
  password = '';
  passwordConfirmation = '';
  readonly passwordPattern =
    /^(?=[\s\S]*[A-Z])(?=[\s\S]*[a-z])(?=[\s\S]*[0-9])(?=[\s\S]*[^A-Za-z0-9\s])[\s\S]+$/;

  get registering() {
    return this.route.snapshot.data['register'] === true;
  }

  get passwordsMatch() {
    return this.passwordConfirmation.length > 0 && this.password === this.passwordConfirmation;
  }

  get passwordFits() {
    // Match the backend's BCrypt byte limit, including passwords with Unicode characters.
    return new TextEncoder().encode(this.password).length <= 72;
  }

  get passwordChecks() {
    return [
      {
        label: '12–64 characters',
        valid: this.password.length >= 12 && this.password.length <= 64
      },
      { label: 'An uppercase letter (A–Z)', valid: /[A-Z]/.test(this.password) },
      { label: 'A lowercase letter (a–z)', valid: /[a-z]/.test(this.password) },
      { label: 'A number (0–9)', valid: /[0-9]/.test(this.password) },
      { label: 'A special character, such as ! or @', valid: /[^A-Za-z0-9\s]/.test(this.password) }
    ];
  }

  fieldState(field: NgModel, extraValid = true): 'required' | 'valid' | 'invalid' {
    // Avoid showing an error before the user interacts with a field or submits the form.
    if (field.pristine && !field.touched && !this.attempted()) return 'required';
    return field.valid && extraValid ? 'valid' : 'invalid';
  }

  fieldLabel(field: NgModel, extraValid = true) {
    const state = this.fieldState(field, extraValid);
    return state === 'valid' ? '✓ Valid' : state === 'invalid' ? 'Needs attention' : 'Required';
  }

  async submit(form: NgForm) {
    if (this.busy()) return;
    this.error.set('');
    this.attempted.set(true);
    form.form.markAllAsTouched();
    if (form.invalid || (this.registering && (!this.passwordsMatch || !this.passwordFits))) {
      // Let Angular render validation attributes before focusing the first invalid input.
      window.setTimeout(() =>
        this.element.nativeElement
          .querySelector<HTMLInputElement>('input[aria-invalid="true"]')
          ?.focus()
      );
      return;
    }
    this.busy.set(true);
    try {
      if (this.registering) {
        await this.auth.register(
          this.name,
          this.email,
          this.password,
          this.passwordConfirmation,
          this.phoneNumber
        );
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
