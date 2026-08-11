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

export interface StartResponse {
	instanceId: string;
	entrance: GameView;
	torchTimeoutSeconds: number;
}

export interface StateResponse {
	instanceId: string;
	status: Status;
	view: GameView;
}

/** One frame off GET /dungeon/{id}/stream. */
export interface StreamEvent {
	kind: 'state' | 'attempt';
	instanceId: string;
	status?: Status;
	view?: GameView;
	attempt?: number;
	picked?: boolean;
}
