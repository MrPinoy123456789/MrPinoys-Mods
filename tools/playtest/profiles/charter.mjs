export default {
  id: 'charter',
  loggers: ['Charter', 'charter'],
  context: player => `charter admin context ${player}`,
  events: [{ pattern: /^Charter (founding|claiming|camp raised|keep fallen|arrest)\b/, kind: 'charter' }],
}
