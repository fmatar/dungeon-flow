import type { StartResponse, StateResponse } from './types';

// All calls go through the /api prefix, which the Vite dev server proxies to the Quarkus
// backend on :8080 (and which the backend itself can serve in production).
const BASE = '/api/dungeon';

async function ok(res: Response): Promise<Response> {
	if (!res.ok) {
		throw new Error(`${res.status} ${res.statusText}`);
	}
	return res;
}

/** POST /dungeon - start a new session. */
export async function startGame(): Promise<StartResponse> {
	const res = await ok(await fetch(BASE, { method: 'POST' }));
	return res.json();
}

/** POST /dungeon/{id}/choice - turn left or right at the fork. */
export async function choose(id: string, direction: 'left' | 'right' | string): Promise<void> {
	await ok(
		await fetch(`${BASE}/${id}/choice`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ direction })
		})
	);
}

/** POST /dungeon/{id}/lever-a|b - pull a lever in the Lever Room. */
export async function pullLever(id: string, which: 'a' | 'b'): Promise<void> {
	await ok(await fetch(`${BASE}/${id}/lever-${which}`, { method: 'POST' }));
}

/** GET /dungeon/{id} - inspect a session. */
export async function inspect(id: string): Promise<StateResponse> {
	const res = await ok(await fetch(`${BASE}/${id}`));
	return res.json();
}

/** GET /dungeon - list all active sessions (race view). */
export async function listGames(): Promise<StateResponse[]> {
	const res = await ok(await fetch(BASE));
	return res.json();
}

/** DELETE /dungeon/{id} - cancel and forget a session. */
export async function cleanup(id: string): Promise<void> {
	await ok(await fetch(`${BASE}/${id}`, { method: 'DELETE' }));
}

/** URL for the SSE stream of a session's live transitions. */
export function streamUrl(id: string): string {
	return `${BASE}/${id}/stream`;
}
