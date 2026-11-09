import type { En } from '../en'

export const main: En['main'] = {
  files: {
    openTitle: 'Abrir script SQL',
    sqlScripts: 'Scripts SQL',
    allFiles: 'Todos los archivos',
    chooseFiles: 'Elegir archivos',
    notAFile: 'No es un archivo.',
    tooLarge: 'El archivo supera los {mb} MB.'
  },
  engine: {
    notRunning: 'El motor no está en ejecución.',
    javaMissing: 'No se encontró Java ({command}). Instale Java 17 o posterior, o defina JAVA_HOME.',
    couldNotStart: 'No se pudo iniciar el motor: {reason}',
    stopped: 'El motor se detuvo ({reason}). Vuelva a intentarlo.',
    jarMissing: 'No se encontró el jar del motor en {path}. Compile el backend o defina AMBER_ENGINE_JAR en ui/amber-desktop/.env (vea .env.example).',
    exitedAtStart: 'El motor terminó durante el arranque ({reason}). {hint}',
    noAnswer: 'El motor no respondió: {reason}',
    keepsStopping: 'El motor se detiene una y otra vez ({reason}). Reinícielo desde el menú de estado.',
    timedOut: 'El motor no respondió a «{method}» a tiempo.',
    cancelled: 'Solicitud cancelada.',
    unknownMethod: 'Método desconocido {method}.'
  },
  db: {
    timeout: 'La base de datos no respondió en {seconds} segundos. Revise el host, el puerto y la red, y vuelva a intentarlo.',
    unknownConnection: 'Conexión desconocida.',
    noConnection: 'No hay ninguna conexión seleccionada para el modo en línea.'
  },
  drivers: {
    unknown: 'Controlador desconocido.',
    badRequest: 'Solicitud de descarga no válida.',
    cancelled: 'Descarga cancelada.',
    timeout: 'La descarga no terminó a tiempo.',
    repository: 'El repositorio de controladores debe ser una URL https://. Corríjalo en Configuración → Conexiones.',
    http: 'El repositorio respondió HTTP {status}.',
    size: 'El archivo descargado no tiene el tamaño esperado, así que se eliminó.',
    hash: 'El archivo descargado no coincide con la suma SHA-256 fijada, así que se eliminó.',
    io: 'No se pudo guardar el controlador: {reason}',
    offline: 'No se pudo acceder al repositorio ({reason}).'
  },
  ai: {
    cancelled: 'Cancelado',
    httpOnly: 'Use una URL http o https.',
    providerAnswered: 'El proveedor respondió {status}',
    addKey: 'Primero agregue una clave de API.',
    noModelList: 'El proveedor no devolvió una lista de modelos. Escriba el nombre del modelo.',
    connectedPick: 'Conectado. {count, plural, one {# modelo disponible} other {# modelos disponibles}}; elija uno.',
    modelMissing: 'Conectado, pero el modelo «{model}» no está instalado.',
    modelAvailable: 'Conectado. El modelo «{model}» está disponible.',
    modelNotListed: 'Conectado. El modelo «{model}» no figura en la lista del proveedor; puede que funcione igualmente.',
    connected: 'Conectado.',
    pickModel: 'Primero elija un modelo en Configuración.',
    emptyResponse: 'El proveedor devolvió una respuesta vacía.',
    providerError: 'Error del proveedor'
  },
  settings: {
    noSecureStorage: 'El almacenamiento seguro no está disponible en este sistema, así que no se pueden guardar secretos.'
  },
  updates: {
    githubAnswered: 'GitHub respondió {status}.'
  }
}
