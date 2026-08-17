// Shapes mirrored from the Quarkus backend (org.acme.dungeon).

export type Room = 'ENTRANCE' | 'FORK' | 'LEVER_ROOM' | 'TRAP_CORRIDOR' | 'TREASURE_ROOM';

export type Status =
	| 'PENDING'
	| 'RUNNING'
	| 'WAITING'
	| 'COMPLETED'
	| 'FAULTED'
	| 'CANCELLED'
	| 'SUSPENDED';

export interface GameView {
	room: Room;
	narrative: string;
	hint: string;
	victory: boolean;
}

export interface PlayerStats {
	strength: number;
	dexterity: number;
	intellect: number;
}

export interface StartResponse {
	instanceId: string;
	entrance: GameView;
	torchTimeoutSeconds: number;
	playerClass: string;
	stats: PlayerStats;
}

export interface StateResponse {
	instanceId: string;
	status: Status;
	view: GameView;
	/** Present only while a riddle gate is holding this instance. */
	riddle?: RiddleView | null;
	playerClass?: string;
	stats?: PlayerStats;
}

/** Coarse warmth bands, computed server-side so every client agrees what "warm" means. */
export type Temperature =
	| 'FREEZING'
	| 'COLD'
	| 'COOL'
	| 'WARM'
	| 'HOT'
	| 'SCALDING'
	| 'SOLVED';

/**
 * The gate currently holding the player. Deliberately never includes the answer — the client is on a
 * projector during demos, and an answer in the network tab is an answer on the wall.
 */
export interface RiddleView {
	riddleId: string;
	prompt: string;
	/** Escalating hint, revealed only after a failed attempt. */
	hint?: string | null;
	attempt: number;
	maxAttempts: number;
	/** 0.0–1.0 closeness of the last answer. Drives the thermometer. */
	proximity: number;
	solved: boolean;
	direction: string;
}

/** One frame off GET /dungeon/{id}/stream. */
export interface StreamEvent {
	kind: 'state' | 'attempt' | 'riddle';
	instanceId: string;
	status?: Status;
	view?: GameView;
	attempt?: number;
	picked?: boolean;
	riddle?: RiddleView;
}
