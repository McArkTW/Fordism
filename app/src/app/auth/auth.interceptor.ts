import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

const MUTATING = ['POST', 'PUT', 'DELETE', 'PATCH'];

/**
 * Two jobs. Every mutating request carries `X-Fordism-Request: 1` — the backend's CSRF stance:
 * a cross-site form can't set custom headers, so it refuses mutations without one. And a 401
 * from anywhere but /api/auth/* means the session died under us — go (back) to /login.
 * /api/auth/* is exempt because its 401s are answers (not logged in; wrong password), not
 * session loss.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const request = MUTATING.includes(req.method)
    ? req.clone({ setHeaders: { 'X-Fordism-Request': '1' } })
    : req;
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !req.url.startsWith('/api/auth/')
      ) {
        router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return throwError(() => error);
    }),
  );
};
