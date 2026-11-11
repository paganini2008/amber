import type { En } from '../en'

export const dialogs: En['dialogs'] = {
  about: {
    title: 'Acerca de Amber',
    slogan: 'Mantenga el SQL heredado que nadie quiere tocar.',
    version: 'Versión {version}',
    privacy: 'Amber nunca ejecuta su SQL. Nada sale de su equipo, salvo que conecte una base de datos (solo metadatos) o un proveedor de IA.',
    versions: 'Versiones',
    copyInfo: 'Copiar información de versión',
    copiedInfo: 'Información de versión copiada.',
    copyFailed: 'No se pudo copiar al portapapeles.',
    engine: 'Motor',
    engineOnJava: '{version} en Java {java}',
    notRunning: 'No está en ejecución',
    starting: 'Iniciando…',
    system: 'Sistema',
    checkUpdates: 'Buscar actualizaciones',
    checkFailed: 'No se pudo comprobar: {reason}',
    available: 'Amber {version} está disponible.',
    latest: 'Tiene la versión más reciente.',
    latestVersion: 'Tiene la versión más reciente ({version}).',
    downloadPage: 'Abrir página de descarga',
    downloading: 'Descargando Amber {version}… {percent} %',
    ready: 'Amber {version} está listo para instalarse.',
    restart: 'Reiniciar para actualizar',
    downloadFailed: 'No se pudo descargar la actualización: {reason}',
    links: 'Enlaces',
    project: 'Proyecto en GitHub',
    website: 'Sitio web',
    releases: 'Versiones publicadas',
    issue: 'Informar de un problema',
    author: 'Autor',
    license: 'Licencia',
    notices: 'Avisos de terceros',
    noticesLead: 'Licencias de los componentes incluidos en Amber.',
    noticesFailed: 'No se pudieron leer los avisos: {reason}'
  },
  paste: {
    title: 'Pegar SQL',
    lead: 'Pegue un script para analizarlo. No se ejecuta nada.',
    name: 'Nombre',
    text: 'Texto SQL'
  },
  handover: {
    title: 'Documento de traspaso',
    lead: 'Qué hace el script, sus objetos, el flujo de datos, los riesgos y las partes que Amber no pudo leer: notas para quien lo mantenga después.',
    writing: 'Redactando el documento de traspaso',
    preview: 'Vista previa del documento de traspaso'
  }
}
