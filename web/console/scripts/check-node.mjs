/**
 * 运行时版本检查：better-sqlite3 预编译包与 Nuxt 4.6 都要求较新的 Node 22。
 * 提前给出可操作的提示，避免原生模块崩溃时只看到 0xC0000005。
 */
const [major, minor] = process.versions.node.split('.').map(Number)
const supported =
  (major === 22 && minor >= 21) || (major === 24 && minor >= 11) || major >= 26

if (!supported) {
  console.error('')
  console.error(`  ✖ 当前 Node v${process.versions.node} 不受支持。`)
  console.error('    CatfeeAgents 控制台需要 Node 22.21+ / 24.11+ / 26+。')
  console.error('')
  console.error('    推荐使用仓库提供的 .nvmrc：')
  console.error('      nvm install 22.23.2')
  console.error('      nvm use 22.23.2')
  console.error('')
  process.exit(1)
}
