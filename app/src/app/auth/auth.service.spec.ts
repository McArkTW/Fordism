import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthUser, AuthService } from './auth.service';

const ADA: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  groups: ['operators'],
  permissions: ['run.*', 'workflow.read'],
  mfaEnabled: false,
};

describe('AuthService', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('mirrors /api/auth/me and answers can() from its grants', async () => {
    const auth = TestBed.inject(AuthService);
    http.expectOne('/api/auth/me').flush(ADA);
    await auth.whenLoaded();

    expect(auth.user()?.displayName).toBe('Ada');
    expect(auth.can('run.answer')).toBe(true);
    expect(auth.can('workflow.read')).toBe(true);
    expect(auth.can('workflow.write')).toBe(false);
    expect(auth.can('user.read')).toBe(false);
  });

  it('treats a 401 from /api/auth/me as "nobody is signed in"', async () => {
    const auth = TestBed.inject(AuthService);
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    await auth.whenLoaded();

    expect(auth.user()).toBeNull();
    // Nothing is permitted without a session — can() must never guess "probably fine".
    expect(auth.can('run.read')).toBe(false);
  });

  it('re-reads the session after a login', async () => {
    const auth = TestBed.inject(AuthService);
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    await auth.whenLoaded();

    const done = auth.login('ada@example.com', 'hunter2');
    const login = http.expectOne('/api/auth/login');
    expect(login.request.body).toEqual({ email: 'ada@example.com', password: 'hunter2' });
    login.flush({});
    // The follow-up /me only goes out once login()'s await resumes — a macrotask drains
    // every microtask queued by the flush.
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/auth/me').flush(ADA);
    await done;

    expect(auth.user()?.email).toBe('ada@example.com');
  });
});
