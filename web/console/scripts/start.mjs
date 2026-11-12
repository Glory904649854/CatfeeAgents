/** 生产模式默认 9777；可通过 PORT 覆盖。 */
process.env.PORT ||= '9777'

await import('../.output/server/index.mjs')
