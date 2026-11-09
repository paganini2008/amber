/** Messages produced by the main process: file dialogs, engine status, AI and database errors, updates. */
export const main = {
  files: {
    openTitle: 'Open SQL script',
    sqlScripts: 'SQL scripts',
    allFiles: 'All files',
    chooseFiles: 'Choose files',
    notAFile: 'Not a file.',
    tooLarge: 'The file is larger than {mb} MB.'
  },
  engine: {
    notRunning: 'The engine is not running.',
    javaMissing: 'Java was not found ({command}). Install Java 17 or later, or set JAVA_HOME.',
    couldNotStart: 'Could not start the engine: {reason}',
    stopped: 'The engine stopped ({reason}). Please retry.',
    jarMissing: 'Engine jar not found at {path}. Run the backend build, or set AMBER_ENGINE_JAR in ui/amber-desktop/.env (see .env.example).',
    exitedAtStart: 'The engine exited during start-up ({reason}). {hint}',
    noAnswer: 'The engine did not answer: {reason}',
    keepsStopping: 'The engine keeps stopping ({reason}). Restart it from the status menu.',
    timedOut: 'The engine did not answer "{method}" in time.',
    cancelled: 'Request cancelled.',
    unknownMethod: 'Unknown method {method}.'
  },
  db: {
    timeout: 'No answer from the database within {seconds} seconds. Check the host, port and network, then try again.',
    unknownConnection: 'Unknown connection.',
    noConnection: 'No connection selected for online mode.'
  },
  drivers: {
    unknown: 'Unknown driver.',
    badRequest: 'Invalid download request.',
    cancelled: 'Download cancelled.',
    timeout: 'The download did not finish in time.',
    repository: 'The driver repository must be an https:// URL. Fix it under Settings → Connections.',
    http: 'The repository answered HTTP {status}.',
    size: 'The downloaded file does not have the expected size, so it was deleted.',
    hash: 'The downloaded file does not match the pinned SHA-256 checksum, so it was deleted.',
    io: 'The driver could not be saved: {reason}',
    offline: 'Could not reach the repository ({reason}).'
  },
  ai: {
    cancelled: 'Cancelled',
    httpOnly: 'Use an http or https URL.',
    providerAnswered: 'The provider answered {status}',
    addKey: 'Add an API key first.',
    noModelList: 'The provider did not return a model list. Type the model name instead.',
    connectedPick: 'Connected. {count, plural, one {# model available} other {# models available}}; pick one.',
    modelMissing: 'Connected, but the model "{model}" is not installed.',
    modelAvailable: 'Connected. Model "{model}" is available.',
    modelNotListed: 'Connected. The model "{model}" is not in the provider\'s list; it may still work.',
    connected: 'Connected.',
    pickModel: 'Pick a model in Settings first.',
    emptyResponse: 'The provider returned an empty response.',
    providerError: 'Provider error'
  },
  settings: {
    noSecureStorage: 'Secure storage is not available on this system, so secrets cannot be saved.'
  },
  updates: {
    githubAnswered: 'GitHub answered {status}.'
  }
}
