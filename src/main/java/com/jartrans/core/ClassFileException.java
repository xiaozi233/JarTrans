package com.jartrans.core;

/** class 文件解析/重写过程中的结构性错误。 */
public class ClassFileException extends Exception {

    private static final long serialVersionUID = 1L;

    public ClassFileException(String message) {
        super(message);
    }

    public ClassFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
