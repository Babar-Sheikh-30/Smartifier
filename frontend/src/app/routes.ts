import { inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { AuthService } from './auth.service';
import { LandingComponent } from './landing';
import { AuthPageComponent } from './auth-page';
import { DashboardComponent } from './dashboard';

const signedIn: CanActivateFn = async (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  try {
    const user = await auth.loadUser();
    return user.role === route.data['role'] ? true : router.parseUrl(auth.dashboard(user));
  } catch (error) {
    return router.createUrlTree(['/login'], {
      queryParams:
        error instanceof HttpErrorResponse && error.status === 401 ? {} : { unavailable: '1' }
    });
  }
};

export const routes: Routes = [
  { path: '', component: LandingComponent },
  { path: 'register', component: AuthPageComponent, data: { register: true } },
  { path: 'login', component: AuthPageComponent, data: { register: false } },
  {
    path: 'member/dashboard',
    component: DashboardComponent,
    canActivate: [signedIn],
    data: { role: 'MEMBER' }
  },
  {
    path: 'admin/dashboard',
    component: DashboardComponent,
    canActivate: [signedIn],
    data: { role: 'ADMIN' }
  },
  { path: '**', redirectTo: '' }
];
