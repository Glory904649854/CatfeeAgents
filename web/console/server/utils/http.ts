export function badRequest(message: string) {
  return createError({ statusCode: 400, statusMessage: message, data: { message } })
}

export function unauthorized(message = '未登录或会话已过期') {
  return createError({ statusCode: 401, statusMessage: message, data: { message } })
}

export function forbidden(message = '没有权限执行该操作') {
  return createError({ statusCode: 403, statusMessage: message, data: { message } })
}

export function notFound(message = '资源不存在') {
  return createError({ statusCode: 404, statusMessage: message, data: { message } })
}

export function conflict(message: string) {
  return createError({ statusCode: 409, statusMessage: message, data: { message } })
}

export function serverError(message = '服务器内部错误') {
  return createError({ statusCode: 500, statusMessage: message, data: { message } })
}
