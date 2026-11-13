package com.vcfcf.adapters.vcommunity.vim;

/**
 * What one vim25 call produced: a value, or why there is none. Every result
 * carries its own fault, so nothing shared between the calls of a pool can
 * be read as another call's reason (design rule 3). The message is
 * credential-free.
 */
public final class VimResult<T> {
    private final T value;
    private final String faultClass;
    private final String message;
    private final String invalidPropertyPath;
    private final long bytes;
    private final Throwable cause;

    private VimResult(T value, String faultClass, String message, String invalidPropertyPath,
            long bytes, Throwable cause) {
        this.value = value;
        this.faultClass = faultClass;
        this.message = message;
        this.invalidPropertyPath = invalidPropertyPath;
        this.bytes = bytes;
        this.cause = cause;
    }

    public static <T> VimResult<T> ok(T value, long bytes) {
        return new VimResult<>(value, null, null, null, bytes, null);
    }

    /**
     * @param faultClass a vim25 fault name without its {@code Fault} suffix
     *        ({@code InvalidLogin}, {@code NotAuthenticated}, {@code InvalidProperty}), or
     *        one of {@code Transport}, {@code TooLarge}, {@code Http}, {@code Closed}, {@code Parse}
     */
    public static <T> VimResult<T> fault(String faultClass, String message,
            String invalidPropertyPath, long bytes, Throwable cause) {
        return new VimResult<>(null, faultClass, message, invalidPropertyPath, bytes, cause);
    }

    public boolean isOk() {
        return faultClass == null;
    }

    /** The value; null when the call failed. */
    public T value() {
        return value;
    }

    public String faultClass() {
        return faultClass;
    }

    /** Why the call failed, credential-free; null when it did not. */
    public String message() {
        return message;
    }

    /** The property path an InvalidProperty fault names, or null. */
    public String invalidPropertyPath() {
        return invalidPropertyPath;
    }

    /** Response bytes read. */
    public long bytes() {
        return bytes;
    }

    /** The underlying exception of a transport failure, or null. */
    public Throwable cause() {
        return cause;
    }
}
