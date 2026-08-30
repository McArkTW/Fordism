import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthUser } from './auth/auth.service';

const ADMIN: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  groups: ['admins'],
  permissions: ['*'],
  mfaEnabled: false,
};

const OPERATOR: AuthUser = { ...ADMIN, groups: ['ops'], permissions: ['run.read'] };

describe('App shell', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  /** Renders the shell with `user` as the answer to /api/auth/me (null flushes a 401). */
  async function shellFor(user: AuthUser | null): Promise<ComponentFixture<App>> {
    const fixture = TestBed.createComponent(App);
    const me = http.expectOne('/api/auth/me');
    if (user) {
      me.flush(user);
    } else {
      me.flush({}, { status: 401, statusText: 'Unauthorized' });
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<App>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders the brand, every nav group and the user chip for an administrator', async () => {
    const content = text(await shellFor(ADMIN));
    expect(content).toContain('Fordism');
    expect(content).toContain('Live');
    expect(content).toContain('History');
    expect(content).toContain('Workflows');
    expect(content).toContain('Credentials');
    expect(content).toContain('Users');
    expect(content).toContain('Groups');
    expect(content).toContain('Ada Lovelace');
  });

  it('hides nav entries the user has no read grant for', async () => {
    const content = text(await shellFor(OPERATOR));
    expect(content).toContain('Live');
    expect(content).toContain('History');
    // A link that can only lead to a refusal is worse than a missing one.
    expect(content).not.toContain('Workflows');
    expect(content).not.toContain('Credentials');
    expect(content).not.toContain('Users');
    expect(content).not.toContain('Groups');
  });

  it('draws no chrome at all when nobody is signed in', async () => {
    // /login and /bootstrap own the whole page — a sidebar there is chrome for an app
    // you cannot reach yet.
    const content = text(await shellFor(null));
    expect(content).not.toContain('Live');
    expect(content).not.toContain('Appearance');
  });
});
