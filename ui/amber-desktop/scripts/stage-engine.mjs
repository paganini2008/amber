// Copies the engine build output (engine.jar + lib/) into resources/engine/ for packaging.
// Leaves resources/runtime/ untouched, and keeps an existing AppCDS archive (amber.jsa),
// which the release job generates next to the jar.
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

const appRoot = resolve(import.meta.dirname, '..')

/** AMBER_ENGINE_JAR from the environment or ui/amber-desktop/.env (the environment wins). */
function engineJarSetting() {
  if (process.env.AMBER_ENGINE_JAR) return process.env.AMBER_ENGINE_JAR
  const file = join(appRoot, '.env')
  if (!existsSync(file)) return null
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*(?:export\s+)?AMBER_ENGINE_JAR\s*=\s*(.*)$/.exec(line)
    if (m) return m[1].replace(/\s+#.*$/, '').replace(/^["']|["']$/g, '').trim() || null
  }
  return null
}

const jarSetting = engineJarSetting()
// The folder of the jar (it holds lib/ next to it), else the default backend build output.
const source = jarSetting ? dirname(resolve(appRoot, jarSetting)) : resolve(appRoot, '..', '..', 'backend', 'amber-core', 'target', 'engine')
const target = join(appRoot, 'resources', 'engine')
const KEEP = new Set(['amber.jsa'])

if (!existsSync(join(source, 'engine.jar'))) {
  console.error(`engine.jar not found in ${source}. Build the backend first (mvn -f backend/pom.xml package).`)
  process.exit(1)
}

mkdirSync(target, { recursive: true })
for (const entry of readdirSync(target)) {
  if (!KEEP.has(entry)) rmSync(join(target, entry), { recursive: true, force: true })
}
cpSync(source, target, { recursive: true })
const libCount = existsSync(join(target, 'lib')) ? readdirSync(join(target, 'lib')).length : 0
console.log(`Staged engine.jar and ${libCount} libraries into ${target}`)
