/**
 * Where a skill lives in the URL.
 *
 * A skill name is namespaced — `access/github`, and nothing stops it being deeper — so it cannot
 * be a path segment without a wildcard route and a hand-rolled re-join. It rides as a query
 * parameter instead, which the router encodes and decodes on its own.
 */
export const SKILL_LIST = '/skills';
export const SKILL_PLUGINS = '/skills/plugins';
export const SKILL_NEW = '/skills/new';
export const SKILL_VIEW = '/skills/view';
export const SKILL_EDIT = '/skills/edit';
