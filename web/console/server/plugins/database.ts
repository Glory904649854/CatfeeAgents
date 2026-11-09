export default defineNitroPlugin(() => {
  const config = useRuntimeConfig()
  initDatabase({
    file: String(config.dbPath),
    adminUser: String(config.adminUser),
    adminPassword: String(config.adminPassword)
  })
})
