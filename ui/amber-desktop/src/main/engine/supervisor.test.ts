import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { resolveEngineCommand } from './supervisor'

describe('resolveEngineCommand', () => {
  it('uses the bundled runtime and AppCDS archive when packaged', () => {
    const res = '/Applications/Amber.app/Contents/Resources'
    const cmd = resolveEngineCommand({
      packaged: true,
      resourcesPath: res,
      appRoot: '/ignored',
      platform: 'darwin',
      env: {},
      exists: () => true
    })
    expect(cmd.command).toBe(join(res, 'runtime', 'bin', 'java'))
    expect(cmd.args).toEqual([
      '-XX:MaxRAMPercentage=50',
      `-XX:SharedArchiveFile=${join(res, 'engine', 'amber.jsa')}`,
      '-jar',
      join(res, 'engine', 'engine.jar'),
      'serve'
    ])
  })

  it('skips the archive when missing and uses java.exe on Windows', () => {
    const cmd = resolveEngineCommand({
      packaged: true,
      resourcesPath: 'C:\\Amber\\resources',
      appRoot: '',
      platform: 'win32',
      env: {},
      exists: () => false
    })
    expect(cmd.command.endsWith('java.exe')).toBe(true)
    expect(cmd.args).toEqual(['-XX:MaxRAMPercentage=50', '-jar', join('C:\\Amber\\resources', 'engine', 'engine.jar'), 'serve'])
  })

  it('prefers AMBER_ENGINE_JAR and JAVA_HOME in development', () => {
    const cmd = resolveEngineCommand({
      packaged: false,
      resourcesPath: '',
      appRoot: '/repo/ui/amber-desktop',
      platform: 'linux',
      env: { AMBER_ENGINE_JAR: '/opt/engine.jar', JAVA_HOME: '/jdk' },
      exists: () => true
    })
    expect(cmd.command).toBe(join('/jdk', 'bin', 'java'))
    // The heap cap applies in development too.
    expect(cmd.args).toEqual(['-XX:MaxRAMPercentage=50', '-jar', '/opt/engine.jar', 'serve'])
  })

  it('falls back to the backend build output and java on PATH', () => {
    const cmd = resolveEngineCommand({
      packaged: false,
      resourcesPath: '',
      appRoot: '/repo/ui/amber-desktop',
      platform: 'linux',
      env: {},
      exists: () => false
    })
    expect(cmd.command).toBe('java')
    expect(cmd.jar).toBe('/repo/backend/amber-core/target/engine/engine.jar')
  })
})
