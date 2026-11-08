import { Menu, shell, type MenuItemConstructorOptions } from 'electron'
import type { MenuCommand } from '../shared/api'
import { tm } from './i18n'

export interface MenuOptions {
  isMac: boolean
  isDev: boolean
  /** GitHub owner/repo for the Help menu link. */
  repo?: string
  send: (command: MenuCommand) => void
}

/**
 * The native menu in the current language (see ./i18n). Role items get explicit labels so they are
 * translated too; rebuild the menu when the language changes.
 */
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
        item(tm('menu.app.about'), 'about'),
        { type: 'separator' },
        item(tm('menu.app.settings'), 'settings', 'Cmd+,'),
        { type: 'separator' },
        { role: 'services', label: tm('menu.app.services') },
        { type: 'separator' },
        { role: 'hide', label: tm('menu.app.hide') },
        { role: 'hideOthers', label: tm('menu.app.hideOthers') },
        { role: 'unhide', label: tm('menu.app.showAll') },
        { type: 'separator' },
        { role: 'quit', label: tm('menu.app.quit') }
      ]
    })
  }
  template.push({
    label: tm('menu.file.title'),
    submenu: [
      item(tm('menu.file.open'), 'open-file', 'CmdOrCtrl+O'),
      item(tm('menu.file.paste'), 'paste-sql', 'CmdOrCtrl+Shift+V'),
      { type: 'separator' },
      item(tm('menu.file.reanalyze'), 'reanalyze', 'CmdOrCtrl+R'),
      item(tm('menu.file.compareSaved'), 'compare-saved', 'CmdOrCtrl+Shift+D'),
      item(tm('menu.file.compareFile'), 'compare-file'),
      { type: 'separator' },
      {
        label: tm('menu.file.export'),
        submenu: [
          item(tm('menu.file.exportSvg'), 'export-svg'),
          item(tm('menu.file.exportPng'), 'export-png'),
          item(tm('menu.file.exportMermaid'), 'export-mermaid'),
          item(tm('menu.file.exportHandover'), 'export-handover'),
          item(tm('menu.file.exportJson'), 'export-json')
        ]
      },
      ...(isMac ? [] : ([{ type: 'separator' }, item(tm('menu.file.settings'), 'settings', 'Ctrl+,'), { role: 'quit', label: tm('menu.file.quit') }] as MenuItemConstructorOptions[]))
    ]
  })
  template.push({
    label: tm('menu.edit.title'),
    submenu: [
      { role: 'undo', label: tm('menu.edit.undo') },
      { role: 'redo', label: tm('menu.edit.redo') },
      { type: 'separator' },
      { role: 'cut', label: tm('menu.edit.cut') },
      { role: 'copy', label: tm('menu.edit.copy') },
      { role: 'paste', label: tm('menu.edit.paste') },
      { role: 'selectAll', label: tm('menu.edit.selectAll') },
      { type: 'separator' },
      // Formatting changes the editor only, as one undoable step; the file is saved by the user.
      item(tm('menu.edit.formatScript'), 'format-script', 'Shift+Alt+F'),
      item(tm('menu.edit.formatSelection'), 'format-selection')
    ]
  })
  template.push({
    label: tm('menu.view.title'),
    submenu: [
      ...(isDev ? ([{ role: 'reload', label: tm('menu.view.reload') }, { role: 'toggleDevTools', label: tm('menu.view.devTools') }, { type: 'separator' }] as MenuItemConstructorOptions[]) : []),
      { role: 'resetZoom', label: tm('menu.view.resetZoom') },
      { role: 'zoomIn', label: tm('menu.view.zoomIn') },
      { role: 'zoomOut', label: tm('menu.view.zoomOut') },
      { type: 'separator' },
      { role: 'togglefullscreen', label: tm('menu.view.fullScreen') }
    ]
  })
  template.push({
    role: 'windowMenu',
    label: tm('menu.window.title'),
    submenu: [
      { role: 'minimize', label: tm('menu.window.minimize') },
      { role: 'zoom', label: tm('menu.window.zoom') },
      ...(isMac
        ? ([{ type: 'separator' }, { role: 'front', label: tm('menu.window.front') }] as MenuItemConstructorOptions[])
        : ([{ role: 'close', label: tm('menu.window.close') }] as MenuItemConstructorOptions[]))
    ]
  })
  template.push({
    role: 'help',
    label: tm('menu.help.title'),
    submenu: [
      ...(isMac ? [] : [item(tm('menu.help.about'), 'about')]),
      { label: tm('menu.help.github'), click: () => void shell.openExternal(`https://github.com/${repo}`) }
    ]
  })
  return Menu.buildFromTemplate(template)
}
