import { contextBridge, ipcRenderer, webUtils, type IpcRendererEvent } from 'electron'
import type { AmberApi, AiStreamEvent, DriverProgress, EngineStatus, MenuCommand, Settings, UpdateStatus } from '../shared/api'

function subscribe<T>(channel: string, listener: (value: T) => void): () => void {
  const handler = (_event: IpcRendererEvent, value: T): void => listener(value)
  ipcRenderer.on(channel, handler)
  return () => {
    ipcRenderer.removeListener(channel, handler)
  }
}

const api: AmberApi = {
  engine: {
    status: () => ipcRenderer.invoke('engine:status'),
    onStatus: (listener) => subscribe<EngineStatus>('engine:status', listener),
    analyze: (params, requestId) => ipcRenderer.invoke('engine:analyze', params, requestId),
    call: (method, params, requestId) => ipcRenderer.invoke('engine:call', method, params, requestId),
    cancel: (requestId) => ipcRenderer.invoke('engine:cancel', requestId),
    restart: () => ipcRenderer.invoke('engine:restart')
  },
  files: {
    openDialog: () => ipcRenderer.invoke('files:openDialog'),
    open: (path) => ipcRenderer.invoke('files:open', path),
    pathForFile: (file) => webUtils.getPathForFile(file),
    save: (request) => ipcRenderer.invoke('files:save', request),
    pickFiles: (request) => ipcRenderer.invoke('files:pickFiles', request)
  },
  settings: {
    get: () => ipcRenderer.invoke('settings:get'),
    update: (patch) => ipcRenderer.invoke('settings:update', patch),
    setAiApiKey: (provider, key) => ipcRenderer.invoke('settings:setAiApiKey', provider, key),
    clearRecentFiles: () => ipcRenderer.invoke('settings:clearRecentFiles'),
    saveConnection: (profile, password) => ipcRenderer.invoke('settings:saveConnection', profile, password),
    deleteConnection: (id) => ipcRenderer.invoke('settings:deleteConnection', id),
    onChanged: (listener) => subscribe<Settings>('settings:changed', listener)
  },
  ai: {
    listOllamaModels: (baseUrl) => ipcRenderer.invoke('ai:listOllamaModels', baseUrl),
    listOpenAiModels: (baseUrl) => ipcRenderer.invoke('ai:listOpenAiModels', baseUrl),
    test: (provider) => ipcRenderer.invoke('ai:test', provider),
    chat: (provider, messages, requestId) => ipcRenderer.invoke('ai:chat', provider, messages, requestId),
    cancel: (requestId) => ipcRenderer.invoke('ai:cancel', requestId),
    onStream: (listener) => subscribe<AiStreamEvent>('ai:stream', listener)
  },
  db: {
    test: (connectionId) => ipcRenderer.invoke('db:test', connectionId),
    loadMetadata: (request, requestId) => ipcRenderer.invoke('db:loadMetadata', request, requestId)
  },
  drivers: {
    list: () => ipcRenderer.invoke('drivers:list'),
    status: (id) => ipcRenderer.invoke('drivers:status', id),
    download: (id, requestId) => ipcRenderer.invoke('drivers:download', id, requestId),
    cancel: (requestId) => ipcRenderer.invoke('drivers:cancel', requestId),
    remove: (id) => ipcRenderer.invoke('drivers:remove', id),
    onProgress: (listener) => subscribe<DriverProgress>('drivers:progress', listener)
  },
  app: {
    info: () => ipcRenderer.invoke('app:info'),
    checkForUpdates: (force) => ipcRenderer.invoke('app:checkForUpdates', force),
    updateStatus: () => ipcRenderer.invoke('app:updateStatus'),
    installUpdate: () => ipcRenderer.invoke('app:installUpdate'),
    onUpdateStatus: (listener) => subscribe<UpdateStatus>('app:updateStatus', listener),
    openExternal: (url) => ipcRenderer.invoke('app:openExternal', url),
    links: () => ipcRenderer.invoke('app:links'),
    thirdPartyNotices: () => ipcRenderer.invoke('app:thirdPartyNotices'),
    onMenu: (listener) => subscribe<MenuCommand>('menu', listener)
  }
}

contextBridge.exposeInMainWorld('amber', api)
