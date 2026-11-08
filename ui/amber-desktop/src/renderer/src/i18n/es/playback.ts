import type { En } from '../en'

export const playback: En['playback'] = {
  title: 'Reproducción de la ejecución',
  stepBack: 'Paso atrás',
  stepForward: 'Paso adelante',
  play: 'Reproducir',
  pause: 'Pausar',
  stop: 'Detener la reproducción',
  position: 'Posición de la reproducción',
  stepOf: 'Paso {step} de {total}',
  speed: 'Velocidad de reproducción',
  stepInside: 'Entrar',
  stepInsideHint: 'Recorrer el orden lógico de esta sentencia en la vista Estructura',
  pending: '{kind} en la línea {line}. Amber no tiene valores de ejecución, así que elija qué se reproduce.',
  insideLoop: 'Dentro de un bucle: se muestra una iteración representativa.',
  oneIteration: 'una iteración representativa',
  decision: {
    all: 'Reproducir los hijos en orden',
    only: 'Reproducir solo {branch}',
    skip: 'Omitir'
  },
  decided: {
    skipped: 'omitido',
    all: 'todo',
    ask: 'preguntar'
  },
  intro: 'Reproduce el script en orden lógico, una sentencia a la vez, para ver cómo se llenan y vacían las tablas. No se ejecuta nada. El esquema, el editor, la matriz del ciclo de vida y el grafo de flujo de datos lo siguen.',
  askLabel: 'Preguntar en cada IF y CASE',
  askLong: 'Pausar y preguntar en cada IF y CASE',
  askShort: 'Preguntar en IF y CASE',
  playFromStart: 'Reproducir desde el inicio',
  manual: 'Avanzar paso a paso',
  branch: 'El motor no sabe qué rama se ejecuta. Elija qué hace la reproducción con {count, plural, one {la # sentencia} other {las # sentencias}} de dentro.',
  reads: 'Lee',
  changes: 'Modifica',
  nothing: 'Nada',
  runOrder: 'Orden de ejecución',
  loop: 'Bucle'
}
