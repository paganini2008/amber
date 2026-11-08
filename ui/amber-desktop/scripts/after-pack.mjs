// electron-builder afterPack hook: restore the modification times of the bundled engine and Java runtime.
// The AppCDS archive (engine/amber.jsa) records the time stamps of engine.jar, lib/*.jar and the runtime's
// modules image; electron-builder's copy gives them new times, and the JVM then refuses the archive
// ("timestamp has changed") and starts without it.
import { existsSync, readdirSync, statSync, utimesSync } from 'node:fs'
import { join } from 'node:path'

function resourcesDir(context) {
  const mac = join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`, 'Contents', 'Resources')
  return existsSync(mac) ? mac : join(context.appOutDir, 'resources')
}

function copyTimes(from, to) {
  if (!existsSync(from) || !existsSync(to)) return 0
  let n = 0
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    const src = join(from, entry.name)
    const dst = join(to, entry.name)
    if (entry.isDirectory()) {
      n += copyTimes(src, dst)
    } else if (entry.isFile() && existsSync(dst)) {
      const s = statSync(src)
      utimesSync(dst, s.atime, s.mtime)
      n++
    }
  }
  return n
}

export default async function afterPack(context) {
  const root = context.packager.projectDir
  const res = resourcesDir(context)
  const n = copyTimes(join(root, 'resources', 'engine'), join(res, 'engine'))
    + copyTimes(join(root, 'resources', 'runtime'), join(res, 'runtime'))
  console.log(`  • afterPack: restored modification times of ${n} engine and runtime files`)
}
