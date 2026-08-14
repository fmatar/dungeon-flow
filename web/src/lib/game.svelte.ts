import { answerRiddle, choose, cleanup, pullLever, startGame, streamUrl } from './api';
import type { GameView, RiddleView, Room, Status, StreamEvent, Temperature } from './types';

export interface Attempt {
	attempt: number;
	picked: boolean;
}

export interface Spotlight {
	tag: string;
	title: string;
	blurb: string;
	dsl: string;
}

/**
 * The live state of one player's session, driven by the SSE stream. A Svelte 5 runes class: the
 * `$state` fields are reactive, so any component that reads them re-renders when the workflow moves.
 *
 * Lever state is tracked optimistically on the client (only this browser pulls this instance's
 * levers), which is what lets us show "join - waiting for lever B". Lock attempts arrive as real
 * `attempt` events from the engine.
 */
export class Game {
	id = $state<string | null>(null);
	view = $state<GameView | null>(null);
	status = $state<Status | null>(null);
	torchSeconds = $state(60);

	leverA = $state(false);
	leverB = $state(false);
	/** The gate currently holding the player, or null when no door is asking a riddle. */
	riddle = $state<RiddleView | null>(null);
	/** True while an answer is in flight, so the form can disable itself. */
	answering = $state(false);
	attempts = $state<Attempt[]>([]);
	forkEntries = $state(0); // bumps on each (re)entry to the fork -> restarts the torch timer
	connected = $state(false);
	busy = $state(false);
	error = $state<string | null>(null);

	private es: EventSource | null = null;

	get room(): Room | null {
		return this.view?.room ?? null;
	}
	get victory(): boolean {
		return this.view?.victory ?? false;
	}
	get done(): boolean {
		return this.status === 'COMPLETED';
	}
	get running(): boolean {
		return this.id !== null;
	}
	/** True when a riddle gate is holding the player at a door. */
	get gated(): boolean {
		return this.riddle !== null && !this.riddle.solved;
	}
	/** Server-computed warmth band, so the UI never invents its own idea of "warm". */
	get temperature(): Temperature {
		const r = this.riddle;
		if (!r) return 'FREEZING';
		if (r.solved) return 'SOLVED';
		if (r.proximity >= 0.75) return 'SCALDING';
		if (r.proximity >= 0.55) return 'HOT';
		if (r.proximity >= 0.4) return 'WARM';
		if (r.proximity >= 0.25) return 'COOL';
		if (r.proximity > 0) return 'COLD';
		return 'FREEZING';
	}

	async start(): Promise<void> {
		this.reset();
		this.busy = true;
		try {
			const s = await startGame();
			this.id = s.instanceId;
			this.view = s.entrance;
			this.torchSeconds = s.torchTimeoutSeconds;
			this.status = 'RUNNING';
			this.connect(s.instanceId);
		} catch (e) {
			this.error = String(e);
		} finally {
			this.busy = false;
		}
	}

	private connect(id: string): void {
		this.es?.close();
		const es = new EventSource(streamUrl(id));
		es.onopen = () => (this.connected = true);
		es.onerror = () => (this.connected = false);
		es.onmessage = (e: MessageEvent) => {
			try {
				this.onEvent(JSON.parse(e.data) as StreamEvent);
			} catch {
				/* ignore malformed frames */
			}
		};
		this.es = es;
	}

	private onEvent(ev: StreamEvent): void {
		if (ev.kind === 'riddle') {
			// A gate was posed, or an answer was graded. Either way this frame IS the gate's state.
			this.riddle = ev.riddle ?? null;
			return;
		}
		if (ev.kind === 'attempt') {
			if (ev.attempt != null) {
				this.attempts = [...this.attempts, { attempt: ev.attempt, picked: !!ev.picked }];
			}
			return;
		}
		if (ev.view) {
			const room = ev.view.room;
			// Leaving the fork means the gate is behind us — solved, or compensated away.
			if (room !== 'FORK') this.riddle = null;
			if (room !== 'LEVER_ROOM') {
				this.leverA = false;
				this.leverB = false;
			}
			if (room === 'TRAP_CORRIDOR') this.attempts = [];
			if (room === 'FORK') this.forkEntries++;
			this.view = ev.view;
		}
		if (ev.status) this.status = ev.status;
	}

	async choose(direction: 'left' | 'right'): Promise<void> {
		if (!this.id) return;
		try {
			await choose(this.id, direction);
		} catch (e) {
			this.error = String(e);
		}
	}

	/** Submit one answer to the gate. Warmth and hints arrive back over SSE, not from this call. */
	async answer(text: string): Promise<void> {
		if (!this.id || this.answering) return;
		this.answering = true;
		try {
			await answerRiddle(this.id, text);
		} catch (e) {
			this.error = String(e);
		} finally {
			this.answering = false;
		}
	}

	async lever(which: 'a' | 'b'): Promise<void> {
		if (!this.id) return;
		if (which === 'a') this.leverA = true;
		else this.leverB = true;
		try {
			await pullLever(this.id, which);
		} catch (e) {
			this.error = String(e);
		}
	}

	async abandon(): Promise<void> {
		if (this.id) {
			try {
				await cleanup(this.id);
			} catch {
				/* best effort */
			}
		}
		this.reset();
	}

	reset(): void {
		this.es?.close();
		this.es = null;
		this.id = null;
		this.view = null;
		this.status = null;
		this.leverA = false;
		this.leverB = false;
		this.riddle = null;
		this.answering = false;
		this.attempts = [];
		this.forkEntries = 0;
		this.connected = false;
		this.error = null;
	}

	/** What workflow primitive is firing right now - the teaching panel's headline. */
	get spotlight(): Spotlight {
		if (!this.running) {
			return {
				tag: 'idle',
				title: 'No game running',
				blurb: 'Start a game to watch the workflow engine drive the dungeon, one primitive at a time.',
				dsl: ''
			};
		}
		if (this.done) {
			return {
				tag: '✦ end',
				title: 'Terminal state reached',
				blurb: 'The instance reached the Treasure Room and completed. In CNCF terms, this state is end: true.',
				dsl: 'withInstanceId("TreasureRoom", …).then(END)'
			};
		}
		if (this.gated) {
			const r = this.riddle!;
			return {
				tag: '⏳ listen  +  🔁 retry',
				title: r.attempt === 0 ? 'Parked on an event wait' : `Bounded retry  (${r.attempt}/${r.maxAttempts})`,
				blurb:
					r.attempt === 0
						? 'The engine is holding on a listen for your answer. It consumes nothing while it waits — it would wait all day.'
						: `Wrong answers do not fault the instance; they loop back to the same listen. On exhaustion a compensation returns you to the fork — the same shape as the trap's lock, guarding a door instead.`,
				dsl: 'listen(toOne("game.riddle.answer")) → switch(solved?) → switch(attempt ≥ max ? compensate : listen)'
			};
		}
		switch (this.room) {
			case 'ENTRANCE':
				return {
					tag: '▶ start',
					title: 'Instance started',
					blurb: 'A fresh workflow instance was created. It walks to the fork and parks on an event wait.',
					dsl: 'workflow("dungeon-flow").tasks( … )'
				};
			case 'FORK':
				return {
					tag: '⏳ listen  +  🔀 switch',
					title: 'Event wait, then a data switch',
					blurb: 'Parked on a listen, waiting for a game.choice event. Your direction routes it through a switch — and a torch timeout is racing you.',
					dsl: 'listen(toOne("game.choice")).timeout("PT…S")  →  switch(left / right)'
				};
			case 'LEVER_ROOM': {
				const n = (this.leverA ? 1 : 0) + (this.leverB ? 1 : 0);
				const waitingFor =
					this.leverA && !this.leverB
						? 'lever B'
						: !this.leverA && this.leverB
							? 'lever A'
							: 'both levers';
				return {
					tag: '🔗 join',
					title: `Multi-event join  (${n}/2)`,
					blurb:
						n === 0
							? 'The gate holds until BOTH lever events arrive — in any order.'
							: n === 1
								? `One lever thrown. The join is holding, waiting for ${waitingFor}.`
								: 'Both levers in — the join releases and the gate opens.',
					dsl: 'listen(to().all("game.lever.a", "game.lever.b"))'
				};
			}
			case 'TRAP_CORRIDOR': {
				const n = this.attempts.at(-1)?.attempt ?? 0;
				return {
					tag: '🔁 retry',
					title: `Bounded retry  (attempt ${Math.max(n, 1)})`,
					blurb:
						'The lock-pick jams at random. The workflow retries it in a loop; on exhaustion a compensation respawns you to the fork.',
					dsl: 'PickLock → switch(picked?) → switch(attempt ≥ 3 ? respawn : retry)'
				};
			}
			default:
				return { tag: '…', title: 'Working', blurb: '', dsl: '' };
		}
	}
}
