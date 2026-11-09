export default defineEventHandler((event) => {
  const id = String(getRouterParam(event, 'id') ?? '')
  return controlTask(event, id, 'stop')
})
