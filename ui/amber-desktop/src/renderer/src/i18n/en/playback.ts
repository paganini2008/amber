/** Execution playback: stepping through the statements in run order. Nothing is executed. */
export const playback = {
  title: 'Execution playback',
  stepBack: 'Step back',
  stepForward: 'Step forward',
  play: 'Play',
  pause: 'Pause',
  stop: 'Stop playback',
  position: 'Playback position',
  stepOf: 'Step {step} of {total}',
  speed: 'Playback speed',
  stepInside: 'Step inside',
  stepInsideHint: 'Walk through this statement\'s logical order in the Structure view',
  pending: '{kind} at line {line}. Amber has no runtime values, so choose what plays.',
  insideLoop: 'Inside a loop: shown as one representative iteration.',
  oneIteration: 'one representative iteration',
  decision: {
    all: 'Play children in order',
    only: 'Play {branch} only',
    skip: 'Skip'
  },
  decided: {
    skipped: 'skipped',
    all: 'all',
    ask: 'ask'
  },
  intro: 'Replays the script in logical order, one statement at a time, so you can watch tables fill up and empty. Nothing is executed. The outline, editor, lifecycle matrix and data-flow graph follow along.',
  askLabel: 'Ask at each IF and CASE',
  askLong: 'Pause and ask at each IF and CASE',
  askShort: 'Ask at IF and CASE',
  playFromStart: 'Play from the start',
  manual: 'Step through manually',
  branch: 'The engine does not know which branch runs. Choose what playback does with the {count, plural, one {# statement} other {# statements}} inside.',
  reads: 'Reads',
  changes: 'Changes',
  nothing: 'Nothing',
  runOrder: 'Run order',
  loop: 'Loop'
}
