import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { History } from './history';

const PAGE_LIMIT = '25';

describe('History', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'runs', component: History }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders with no query params without throwing', async () => {
    // Regression: absent params used to reach .split() as undefined and take the page down.
    const harness = await RouterTestingHarness.create('/runs');

    // Two calls hit /api/runs on init: the workflow picker (limit=200) and the first page.
    const requests = http.match((req) => req.url === '/api/runs');
    expect(requests.length).toBe(2);
    const page = requests.find((req) => req.request.params.get('limit') === PAGE_LIMIT);
    expect(page).toBeDefined();
    // HttpParams.getAll is null (not []) for an absent key — either way, no state filter went out.
    expect(page!.request.params.getAll('state') ?? []).toEqual([]);
    requests.forEach((req) => req.flush([]));

    harness.detectChanges();
    expect(harness.routeNativeElement?.textContent).toContain('History');
  });

  it('applies both repeated state params (?state=A&state=B) to the filter', async () => {
    // Regression: paramMap.get('state') only returned the first value; getAll must be used.
    await RouterTestingHarness.create('/runs?state=DONE&state=FAILED');

    const requests = http.match((req) => req.url === '/api/runs');
    const page = requests.find((req) => req.request.params.get('limit') === PAGE_LIMIT);
    expect(page).toBeDefined();
    expect(page!.request.params.getAll('state')).toEqual(['DONE', 'FAILED']);
    requests.forEach((req) => req.flush([]));
  });
});
