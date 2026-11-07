import { Menu, shell, type MenuItemConstructorOptions } from 'electron'
import type { MenuCommand } from '../shared/api'

export interface MenuOptions {
  isMac: boolean
  isDev: boolean
  /** GitHub owner/repo for the Help menu link. */
  repo?: string
  send: (command: MenuCommand) => void
}

export function buildMenu({ isMac, isDev, send, repo = 'paganini2008/amber' }: MenuOptions): Menu {
  const item = (label: string, command: MenuCommand, accelerator?: string): MenuItemConstructorOptions => ({
    label,
    accelerator,
    click: () => send(command)
  })

  const template: MenuItemConstructorOptions[] = []
  if (isMac) {
    template.push({
      label: 'Amber',
      submenu: [
        item('About Amber', 'about'),
        { type: 'separator' },
        item('Settings…', 'settings', 'Cmd+,'),
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' }
      ]
    })
  }
  template.push({
    label: 'File',
    submenu: [
      item('Open script…', 'open-file', 'CmdOrCtrl+O'),
      item('Paste SQL…', 'paste-sql', 'CmdOrCtrl+Shift+V'),
      { type: 'separator' },
      item('Analyze again', 'reanalyze', 'CmdOrCtrl+R'),
      item('Compare with the saved file', 'compare-saved', 'CmdOrCtrl+Shift+D'),
      item('Compare with another version…', 'compare-file'),
      { type: 'separator' },
      {
        label: 'Export',
        submenu: [
          item('Current view as SVG…', 'export-svg'),
          item('Current view as PNG…', 'export-png'),
          item('Current view as Mermaid…', 'export-mermaid'),
          item('Handover document…', 'export-handover'),
          item('Analysis as JSON…', 'export-json')
        ]
      },
      ...(isMac ? [] : ([{ type: 'separator' }, item('Settings…', 'settings', 'Ctrl+,'), { role: 'quit' }] as MenuItemConstructorOptions[]))
    ]
  })
  template.push({
    label: 'Edit',
    submenu: [
      { role: 'undo' },
      { role: 'redo' },
      { type: 'separator' },
      { role: 'cut' },
      { role: 'copy' },
      { role: 'paste' },
      { role: 'selectAll' },
      { type: 'separator' },
      // Formatting changes the editor only, as one undoable step; the file is saved by the user.
      item('Format script', 'format-script', 'Shift+Alt+F'),
      item('Format selection', 'format-selection')
    ]
  })
  template.push({
    label: 'View',
    submenu: [
      ...(isDev ? ([{ role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' }] as MenuItemConstructorOptions[]) : []),
      { role: 'resetZoom' },
      { role: 'zoomIn' },
      { role: 'zoomOut' },
      { type: 'separator' },
      { role: 'togglefullscreen' }
    ]
  })
  template.push({ role: 'windowMenu' })
  template.push({
    role: 'help',
    submenu: [
      ...(isMac ? [] : [item('About Amber', 'about')]),
      { label: 'Amber on GitHub', click: () => void shell.openExternal(`https://github.com/${repo}`) }
    ]
  })
  return Menu.buildFromTemplate(template)
}
