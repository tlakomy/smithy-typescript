/**
 * @public
 *
 * A function that, given a TypedArray of bytes, can produce a string
 * representation thereof.
 *
 * @example An encoder function that converts bytes to hexadecimal
 * representation would return `'deadbeef'` when given
 * `new Uint8Array([0xde, 0xad, 0xbe, 0xef])`.
 */
export interface Encoder {
  (input: Uint8Array): string;
}

/**
 * @public
 *
 * A function that, given a string, can derive the bytes represented by that
 * string.
 *
 * @example A decoder function that converts bytes to hexadecimal
 * representation would return `new Uint8Array([0xde, 0xad, 0xbe, 0xef])` when
 * given the string `'deadbeef'`.
 */
export interface Decoder {
  (input: string): Uint8Array;
}

/**
 * @public
 *
 * A function that, when invoked, returns a promise that will be fulfilled with
 * a value of type T.
 *
 * @example A function that reads credentials from shared SDK configuration
 * files, assuming roles and collecting MFA tokens as necessary.
 */
export interface Provider<T> {
  (): Promise<T>;
}
