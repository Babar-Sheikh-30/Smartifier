import { provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app/routes';

bootstrapApplication(AppComponent, {
  providers: [provideZonelessChangeDetection(), provideHttpClient(), provideRouter(routes)]
}).catch((error) => console.error(error));
