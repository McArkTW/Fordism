import { inject } from '@angular/core';
import { CanActivateChildFn, CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Everything but /login and /bootstrap sits behind this: no session, no page. */
export const authGuard: CanActivateChildFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.whenLoaded();
  if (auth.user()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/**
 * Route-level mirror of a server-side permission check. Cosmetic — the API refuses on its
 * own — but it keeps a lacking user from landing on a page that can only show errors.
 */
export function permissionGuard(permission: string): CanActivateFn {
  return async (_route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);
    await auth.whenLoaded();
    if (!auth.user()) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    }
    return auth.can(permission) ? true : router.createUrlTree(['/']);
  };
}
