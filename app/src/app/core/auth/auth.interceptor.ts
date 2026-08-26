import { HttpInterceptorFn } from '@angular/common/http';

const TOKEN_KEY = 'foundry_id_token';

/** Attach the Heimdall identity token to Foundry's own /api calls. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (typeof localStorage === 'undefined') {
    return next(req); // server-side render: no token
  }
  const token = localStorage.getItem(TOKEN_KEY);
  if (token && req.url.startsWith('/api')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};
