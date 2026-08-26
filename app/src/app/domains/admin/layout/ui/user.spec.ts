import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { UserProfile } from '@/app/core/auth/auth.service';
import { provideIcons } from '@/app/core/icons/provider';
import { provideTheming } from '@/app/core/theming';
import { User } from './user';

const PROFILE: UserProfile = {
  email: 'tony.liu@hp.com',
  displayName: 'tony.liu',
  firstSeen: '2026-08-13T09:00:00Z',
  lastSeen: '2026-08-13T10:00:00Z',
};

/**
 * The account block is the only thing that shows who is signed in, and `ng build` proves
 * nothing about a template binding — these render it for real and read the DOM back.
 */
describe('User', () => {
  let fixture: ComponentFixture<User>;
  let http: HttpTestingController;

  const text = () => fixture.nativeElement.textContent as string;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [User],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideIcons(),
        provideTheming({ scheme: 'dark', primary: '#10b981', error: '#dc2626' }),
      ],
    }).compileComponents();

    localStorage.setItem('foundry_id_token', 'test-token');
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(User);
    fixture.detectChanges();
  });

  it('asks the backend who is signed in', () => {
    http.expectOne('/api/auth/me').flush(PROFILE);
  });

  it('shows the display name and email once loaded', async () => {
    http.expectOne('/api/auth/me').flush(PROFILE);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('tony.liu');
    expect(text()).toContain('tony.liu@hp.com');
  });

  it('builds initials from the display name', async () => {
    http.expectOne('/api/auth/me').flush(PROFILE);
    await fixture.whenStable();
    fixture.detectChanges();

    // "tony.liu" splits on the dot — two initials, not the first two letters.
    expect(text()).toContain('tl');
  });

  it('says it is still signing in before the profile arrives, and shows no email', () => {
    expect(text()).toContain('Signing in');
    expect(text()).not.toContain('@');
    http.expectOne('/api/auth/me').flush(PROFILE);
  });

  it('drops a token the backend no longer accepts', async () => {
    http
      .expectOne('/api/auth/me')
      .flush({ error: 'not logged in' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(localStorage.getItem('foundry_id_token')).toBeNull();
  });

  it('does not call the backend without a token', () => {
    http.expectOne('/api/auth/me').flush(PROFILE); // the fixture built in beforeEach
    localStorage.removeItem('foundry_id_token');

    TestBed.createComponent(User).detectChanges();

    http.expectNone('/api/auth/me');
  });

  afterEach(() => {
    localStorage.removeItem('foundry_id_token');
    http.verify();
  });
});
