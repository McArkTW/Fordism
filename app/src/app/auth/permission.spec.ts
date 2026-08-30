import { PermissionService } from './permission';
import vectors from './permission-matcher-vectors.json';

describe('PermissionService', () => {
  const service = new PermissionService();

  // The vectors file is the shared contract with the backend matcher — the spec is driven by
  // it so the two implementations are pinned to the same behavior, case by case.
  for (const { grant, required, expected } of vectors) {
    it(`${grant} ${expected ? 'matches' : 'does not match'} ${required}`, () => {
      expect(service.matches(grant, required)).toBe(expected);
    });
  }

  it('anyMatches scans a grant list', () => {
    expect(service.anyMatches(['skill.read', 'run.*'], 'run.answer')).toBe(true);
    expect(service.anyMatches(['skill.read', 'run.*'], 'group.read')).toBe(false);
    expect(service.anyMatches([], 'group.read')).toBe(false);
  });
});
