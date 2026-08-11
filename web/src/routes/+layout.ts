// Pure client-side SPA: the game is entirely browser-driven and talks to the backend over
// /api. No SSR, no prerender - every route renders on the client.
export const ssr = false;
export const prerender = false;
export const trailingSlash = 'never';
