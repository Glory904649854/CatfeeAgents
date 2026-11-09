/**
 * vuex@4.1.0 的 package.json exports 没有声明 types 条件，
 * 在 moduleResolution=bundler 下 TypeScript 找不到自带声明。
 * 这里显式转发到包内类型文件，运行时仍然是正常的 vuex 入口。
 */
declare module 'vuex' {
  export * from 'vuex/types/index.d.ts'
}
