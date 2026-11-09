import { mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const dbPath = resolve(process.cwd(), process.env.NUXT_DB_PATH || './data/catfee.db')

for (const file of [dbPath, `${dbPath}-wal`, `${dbPath}-shm`]) {
  rmSync(file, { force: true })
  console.log(`removed ${file}`)
}

mkdirSync(dirname(dbPath), { recursive: true })
console.log(`database reset: ${dbPath}`)
console.log('schema will be recreated on next server start.')
