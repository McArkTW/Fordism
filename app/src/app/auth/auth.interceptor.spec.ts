import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    navigate = vi.fn().mockResolvedValue(true);
    // The redirect is the assertion; actually routing would need the whole app.
    TestBed.inject(Router).navigate = navigate as unknown as Router['navigate'];
  });

  afterEach(() => http.verify());

  it('stamps mutating requests with the CSRF header', () => {
    client.post('/api/groups', { name: 'Ops' }).subscribe();
    const request = http.expectOne('/api/groups');
    // Without it core answers 403: a cross-site form cannot set a custom header.
    expect(request.request.headers.get('X-Fordism-Request')).toBe('1');
    request.flush({});
  });

  it('leaves reads alone', () => {
    client.get('/api/groups').subscribe();
    const request = http.expectOne('/api/groups');
    expect(request.request.headers.has('X-Fordism-Request')).toBe(false);
    request.flush([]);
  });

  it('sends a 401 from an ordinary endpoint back to /login', () => {
    client.get('/api/runs').subscribe({ error: () => undefined });
    http.expectOne('/api/runs').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('leaves a 401 from /api/auth/* alone — that is an answer, not a lost session', () => {
    // A wrong password must render on the login form, not bounce the page it is already on.
    client.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    http.expectOne('/api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('leaves a 403 alone — signed in, just not allowed', () => {
    client.delete('/api/groups/1').subscribe({ error: () => undefined });
    http.expectOne('/api/groups/1').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(navigate).not.toHaveBeenCalled();
  });
});
