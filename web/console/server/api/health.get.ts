export default defineEventHandler(() => {
  return {
    ok: true,
    service: 'catfee-console',
    version: '0.1.0',
    time: new Date().toISOString()
  }
})
