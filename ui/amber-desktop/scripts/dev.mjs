// `npm run dev`: starts electron-vite. On macOS it first prepares a branded copy of the Electron binary in
// .dev/Amber.app (git-ignored) so the menu bar, Dock and Cmd-Tab say "Amber" with Amber's icon during
// development. node_modules is never modified; packaged builds are branded by electron-builder instead.
// Extra arguments are passed to electron-vite (e.g. `npm run dev -- --remoteDebuggingPort 9333`).
import { execFileSync, spawn } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const env = { ...process.env }

function brandedApp() {
  const source = join(root, 'node_modules', 'electron', 'dist', 'Electron.app')
  const png = join(root, 'build', 'icon.png')
  const target = join(root, '.dev', 'Amber.app')
  const stamp = join(root, '.dev', 'electron-version')
  const version = JSON.parse(readFileSync(join(root, 'node_modules', 'electron', 'package.json'), 'utf8')).version
  const executable = join(target, 'Contents', 'MacOS', 'Electron')
  if (existsSync(executable) && existsSync(stamp) && readFileSync(stamp, 'utf8') === version) return executable
  if (!existsSync(source) || !existsSync(png)) return null

  const run = (cmd, args) => execFileSync(cmd, args, { stdio: 'ignore' })
  rmSync(target, { recursive: true, force: true }) // only our own copy under .dev/
  mkdirSync(dirname(target), { recursive: true })
  cpSync(source, target, { recursive: true, verbatimSymlinks: true })

  const work = mkdtempSync(join(tmpdir(), 'amber-icon-'))
  const set = join(work, 'icon.iconset')
  mkdirSync(set)
  for (const size of [16, 32, 128, 256, 512]) {
    run('sips', ['-z', String(size), String(size), png, '--out', join(set, `icon_${size}x${size}.png`)])
    run('sips', ['-z', String(size * 2), String(size * 2), png, '--out', join(set, `icon_${size}x${size}@2x.png`)])
  }
  run('iconutil', ['-c', 'icns', set, '-o', join(target, 'Contents', 'Resources', 'electron.icns')])
  rmSync(work, { recursive: true, force: true })

  const plist = join(target, 'Contents', 'Info.plist')
  for (const key of ['CFBundleName', 'CFBundleDisplayName']) {
    try {
      run('plutil', ['-replace', key, '-string', 'Amber', plist])
    } catch {
      run('plutil', ['-insert', key, '-string', 'Amber', plist])
    }
  }
  // Editing the bundle breaks Electron's ad-hoc signature; Apple silicon refuses unsigned code.
  run('codesign', ['--force', '--deep', '--sign', '-', target])
  writeFileSync(stamp, version)
  return executable
}

if (process.platform === 'darwin' && !env.ELECTRON_EXEC_PATH) {
  try {
    const exe = brandedApp()
    if (exe) env.ELECTRON_EXEC_PATH = exe
  } catch (error) {
    console.warn(`Using the plain Electron binary (could not prepare .dev/Amber.app: ${error instanceof Error ? error.message : error})`)
  }
}

const bin = join(root, 'node_modules', '.bin', process.platform === 'win32' ? 'electron-vite.cmd' : 'electron-vite')
const child = spawn(bin, ['dev', ...process.argv.slice(2)], { stdio: 'inherit', env, shell: process.platform === 'win32' })
child.on('exit', (code, signal) => process.exit(signal ? 1 : (code ?? 0)))
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => child.kill(sig))
