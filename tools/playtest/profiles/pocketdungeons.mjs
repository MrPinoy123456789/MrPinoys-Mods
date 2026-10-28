export default {
  id: 'pocketdungeons',
  loggers: ['PocketDungeons'],
  context: player => `dungeon admin context ${player}`,
  events: [{ pattern: /completed floor/, kind: 'floor' }],
}
