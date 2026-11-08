import type { En } from '../en'

export const menu: En['menu'] = {
  app: {
    about: 'Acerca de Amber',
    settings: 'Configuración…',
    services: 'Servicios',
    hide: 'Ocultar Amber',
    hideOthers: 'Ocultar otros',
    showAll: 'Mostrar todo',
    quit: 'Salir de Amber'
  },
  file: {
    title: 'Archivo',
    open: 'Abrir script…',
    paste: 'Pegar SQL…',
    reanalyze: 'Analizar de nuevo',
    compareSaved: 'Comparar con el archivo guardado',
    compareFile: 'Comparar con otra versión…',
    export: 'Exportar',
    exportSvg: 'Vista actual como SVG…',
    exportPng: 'Vista actual como PNG…',
    exportMermaid: 'Vista actual como Mermaid…',
    exportHandover: 'Documento de traspaso…',
    exportJson: 'Análisis como JSON…',
    settings: 'Configuración…',
    quit: 'Salir'
  },
  edit: {
    title: 'Edición',
    undo: 'Deshacer',
    redo: 'Rehacer',
    cut: 'Cortar',
    copy: 'Copiar',
    paste: 'Pegar',
    selectAll: 'Seleccionar todo',
    formatScript: 'Formatear script',
    formatSelection: 'Formatear selección'
  },
  view: {
    title: 'Ver',
    reload: 'Recargar',
    devTools: 'Herramientas de desarrollo',
    resetZoom: 'Tamaño real',
    zoomIn: 'Acercar',
    zoomOut: 'Alejar',
    fullScreen: 'Pantalla completa'
  },
  window: {
    title: 'Ventana',
    minimize: 'Minimizar',
    zoom: 'Zoom',
    close: 'Cerrar ventana',
    front: 'Traer todo al frente'
  },
  help: {
    title: 'Ayuda',
    about: 'Acerca de Amber',
    github: 'Amber en GitHub'
  }
}
