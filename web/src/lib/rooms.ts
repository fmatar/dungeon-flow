import type { Room } from './types';

/** Linear order of the dungeon, used for the room map and the race progress track. */
export const ROOM_ORDER: Room[] = [
	'ENTRANCE',
	'FORK',
	'LEVER_ROOM',
	'TRAP_CORRIDOR',
	'TREASURE_ROOM'
];

export const ROOM_LABEL: Record<Room, string> = {
	ENTRANCE: 'Entrance',
	FORK: 'Fork',
	LEVER_ROOM: 'Lever Room',
	TRAP_CORRIDOR: 'Trap Corridor',
	TREASURE_ROOM: 'Treasure'
};

export const ROOM_GLYPH: Record<Room, string> = {
	ENTRANCE: '⌂',
	FORK: 'Y',
	LEVER_ROOM: '⚙',
	TRAP_CORRIDOR: '#',
	TREASURE_ROOM: '✦'
};

export function roomIndex(room: Room | null | undefined): number {
	return room ? ROOM_ORDER.indexOf(room) : -1;
}
